(ns satellite.core
  (:require [cemerick.url :as url]
            [clj-http.client :as client]
            [clj-logging-config.log4j :as log4j-conf]
            [clojurewerkz.welle.core :as wc]
            [clojure.tools.logging :as log]
            [liberator.core :refer (resource)]
            [plumbing.core :refer (fnk)]
            [plumbing.core]
            [plumbing.graph :as graph]
            [satellite.recipes]
            [satellite.riemann :as riemann]
            [satellite.riemann.monitor :as monitor]
            [satellite.riemann.services.cache-merge]
            [satellite.riemann.services.curator]
            [satellite.riemann.services.http]
            [satellite.riemann.services.leader]
            [satellite.riemann.services.whitelist]
            [satellite.time :as time]
            [satellite.util :as util]
            [satellite.whitelist :as whitelist]
            [schema.core :as s])
  (:gen-class))

(defn file-exists?
  [f]
  (.exists (clojure.java.io/as-file f)))

(def riemann-tcp-server-schema
  {(s/optional-key :host) s/Str
   (s/optional-key :port) s/Int})

(def settings-schema
  {;; the Riemann config file that will process events
   :riemann-config (s/pred file-exists? 'file-exists?)
   :riemann-tcp-server-options riemann-tcp-server-schema
   :sleep-time s/Int
   :mesos-master-url cemerick.url.URL
   :riak (s/maybe {:endpoint (s/pred clojure.java.io/as-url)
                   :bucket s/Str})
   :service-host s/Str
   :service-port s/Int
   :zookeeper s/Str
   :curator-retry-policy {:base-sleep-time-ms s/Int
                          :max-sleep-time-ms s/Int
                          :max-retries s/Int}
   :local-whitelist-path s/Str
   :local-manual-whitelist-path s/Str
   :local-managed-whitelist-path s/Str
   :whitelist-hostname-pred (s/pred clojure.test/function? 'clojure.test/function)})

(def settings
  ;; Settings for the Satellite monitor/service
  ;;
  ;; Current values are used as the defaults; to override them, assoc values
  ;; into this var
  {;; the Riemann config file that will process events
   :riemann-config "config/riemann-config.clj"
   ;; if you do not explicitly bind you will just get localhost
   :riemann-tcp-server-options {}
   ;; the sleep time between loops of polling Mesos Master endpoints to create
   ;; the event stream
   :sleep-time 60000
   ;; a cemerick.url.URL record type
   :mesos-master-url (url/url "http://localhost:5050")
   ;; Riak endpoint serving cached task metadata, nil if not using
   ;;    {:endpoint "http://uri/to/riak"
   ;;     :bucket "bucket-name"}
   :riak nil
   ;; service
   :service-host nil
   ;; Port on which the service is publicly accessible
   :service-port 5001
   ;; Zookeeper string used for whitelist co-ordination
   :zookeeper "zk1:port,zk2:port,zk3:port"
   ;; Curator retry policy
   :curator-retry-policy {:base-sleep-time-ms 100
                          :max-sleep-time-ms 120000
                          :max-retries 10}
   ;; the path on disk to the Mesos whitelist
   :local-whitelist-path "whitelist"
   ;; the path on disk to put the manual Mesos whitelist
   :local-manual-whitelist-path "manual-whitelist"
   ;; the path on disk to put hte managed Mesos whitelist
   :local-managed-whitelist-path "managed-whitelist"
   ;; predicate used to validate hosts that are added to the whitelist
   :whitelist-hostname-pred (fn [hostname]
                              (identity hostname))})

(defn map->graph
  [m]
  (plumbing.core/map-vals (fn [x] (fnk [] x)) m))

(defn app
  [settings]
  {:settings settings
   :riak-conn (fnk [[:settings riak]]
                   (when riak
                     (wc/connect (:endpoint riak))))
   :curator (fnk [[:settings zookeeper curator-retry-policy]]
                 (riemann.config/service!
                  (satellite.riemann.services.curator/curator-service
                   zookeeper curator-retry-policy)))
   :leader (fnk [[:settings mesos-master-url]]
                (riemann.config/service!
                 (satellite.riemann.services.leader/leader-service
                  mesos-master-url)))
   ;; the path on Zookeeper to the whitelist coordination node
   :zk-whitelist-path (fnk [] "/whitelist")
   :zk-manual-whitelist-path (fnk [] "/manual-whitelist")
   :zk-managed-whitelist-path (fnk [] "/managed-whitelist")
   :whitelist-sync (fnk [curator leader zk-whitelist-path
                         [:settings local-whitelist-path]]
                        (riemann.config/service!
                         (satellite.riemann.services.whitelist/whitelist-sync-service
                          curator zk-whitelist-path
                          local-whitelist-path local-whitelist-path
                          (:leader? leader))))
   :manual-whitelist-sync (fnk [curator leader zk-manual-whitelist-path
                                [:settings local-manual-whitelist-path]]
                               (riemann.config/service!
                                (satellite.riemann.services.whitelist/whitelist-sync-service
                                 curator zk-manual-whitelist-path
                                 local-manual-whitelist-path nil
                                 (:leader? leader))))
   :managed-whitelist-sync (fnk [curator leader zk-managed-whitelist-path
                                 [:settings
                                  local-managed-whitelist-path
                                  local-whitelist-path]]
                                (let [managed-sync
                                      (satellite.riemann.services.whitelist/whitelist-sync-service
                                       curator zk-managed-whitelist-path
                                       local-managed-whitelist-path local-whitelist-path
                                       (:leader? leader))]
                                  ;; see note in recipes
                                  (future
                                    (intern 'satellite.recipes
                                            'on-host
                                            (fn [host]
                                              (whitelist/on-host
                                               (:cache @(:syncer managed-sync))
                                               @(:curator curator)
                                               zk-managed-whitelist-path
                                               host)))
                                    (intern 'satellite.recipes
                                            'off-host
                                            (fn [host]
                                              (whitelist/off-host
                                               (:cache @(:syncer managed-sync))
                                               @(:curator curator)
                                               zk-managed-whitelist-path
                                               host))))
                                  (riemann.config/service! managed-sync)))
   :cache-merge (fnk [curator zk-whitelist-path
                      whitelist-sync managed-whitelist-sync manual-whitelist-sync]
                     (riemann.config/service!
                      (satellite.riemann.services.cache-merge/cache-merge-service
                       curator zk-whitelist-path
                       (:syncer whitelist-sync)
                       (:syncer managed-whitelist-sync)
                       (:syncer manual-whitelist-sync)
                       (-> 1 time/seconds))))
   ;; if you want a riak-conn, do not start until you have it
   :http-service (fnk [[:settings
                        service-host service-port riak
                        whitelist-hostname-pred]
                       curator riak-conn whitelist-sync manual-whitelist-sync
                       zk-manual-whitelist-path]
                      (riemann.config/service!
                       (satellite.riemann.services.http/http-service
                        {:syncer (:syncer whitelist-sync)
                         :manual-syncer (:syncer manual-whitelist-sync)
                         :curator (:curator curator)
                         :riak riak
                         :riak-conn riak-conn
                         :whitelist-hostname-pred whitelist-hostname-pred
                         :zk-whitelist-path zk-manual-whitelist-path}
                        service-host
                        service-port)))
   :riemann-core (fnk [curator http-service leader whitelist-sync
                       managed-whitelist-sync manual-whitelist-sync
                       cache-merge]
                      riemann.config/core)
   :riemann (fnk [[:settings riemann-config] riemann-core]
                 (try
                   (intern 'riemann.config
                           'leader?
                           (fn [] nil))
                   (riemann/start-riemann riemann-config)
                   (catch Throwable t
                     (log/error t "Riemann failed"))))
   :monitor (fnk [[:settings
                   sleep-time riemann-tcp-server-options]
                  leader riemann riemann-core]
                 (future
                   (try
                     (monitor/do-monitor {:leader leader
                                          :core riemann-core
                                          :opts riemann-tcp-server-options
                                          :sleep-time sleep-time})
                     (catch Throwable t
                       (log/error t "Monitor failed")))))})

(defn init-logging
  []
  (log4j-conf/set-loggers! (org.apache.log4j.Logger/getRootLogger)
                           {:out (org.apache.log4j.DailyRollingFileAppender.
                                  (org.apache.log4j.PatternLayout.
                                   "%d{ISO8601} %-5p %c [%t] - %m%n")
                                  "log/satellite.log"
                                  "'.'yyyy-MM-dd")
                            :level :info}))

(defn -main
  [& [config args]]
  (init-logging)
  (log/info "Starting Satellite")
  (if (and config
           (.exists (java.io.File. config)))
    (do (log/info (str "Reading config from file: " config))
        (load-file config))
    (log/info (str "Using default settings" settings)))
  (s/validate settings-schema settings)
  ((graph/eager-compile (app (map->graph settings))) {}))

(comment
  (init-logging)
  (def inst ((graph/eager-compile (app settings)) {}))
  (def inst (-main "config/satellite-config.clj "))
  (@(:cli-server inst))

  (require 'riemann.core)
  (require 'riemann.transport)
  (require 'riemann.config)

  (satellite.riemann/reload!))
