(ns satellite.core
  (:require [cemerick.url :as url]
            [clj-http.client :as client]
            [clj-logging-config.log4j :as log4j-conf]
            [clojurewerkz.welle.core :as wc]
            [clojure.tools.logging :as log]
            [liberator.core :refer (resource)]
            [plumbing.core :refer (fnk)]
            [plumbing.graph :as graph]
            [satellite.riemann :as riemann]
            [satellite.riemann.monitor :as monitor]
            [satellite.riemann.services.curator]
            [satellite.riemann.services.http]
            [satellite.riemann.services.leader]
            [satellite.riemann.services.whitelist]
            [satellite.time :as time]
            [satellite.util :as util]
            [satellite.whitelist :as whitelist])
  (:gen-class))

(def settings
  ;; Settings for the Satellite monitor/service
  ;;
  ;; Current values are used as the defaults; to override them, assoc values
  ;; into this var
  {;; the Riemann config file that will process events
   :riemann-config (fnk [] "config/riemann-config.clj")
   ;;
   :riemann-tcp-server-options (fnk [] {})
   ;; the sleep time between loops of polling Mesos Master endpoints to create
   ;; the event stream
   :sleep-time (fnk [] 60000)
   ;; a cemerick.url.URL record type
   :mesos-master-url (fnk [] (url/url "http://localhost:5050"))
   ;; Riak endpoint serving cached task metadata, nil if not using
   :riak (fnk []
              {:endpoint "http://uri/to/riak"
               :bucket "bucket-name"})
   ;; service
   :service-host (fnk [] nil)
   ;; Port on which the service is publicly accessible
   :service-port (fnk [] 5001)
   ;; Zookeeper string used for whitelist co-ordination
   :zookeeper (fnk [] "zk1:port,zk2:port,zk3:port")
   ;; Curator retry policy
   :curator-retry-policy (fnk []
                              {:base-sleep-time-ms 100
                               :max-sleep-time-ms 120000
                               :max-retries 10})
   ;; the path on disk to the Mesos whitelist
   :local-whitelist-path (fnk [] "resources/whitelist")
   ;; the path on Zookeeper to the whitelist coordination node
   :zk-whitelist-path (fnk [] "/whitelist")
   ;; predicate used to validate hosts that are added to the whitelist
   :whitelist-hostname-pred (fnk []
                                 (fn [hostname]
                                   (identity hostname)))})

(defn app
  [settings]
  {:settings settings
   :riak-conn (fnk [[:settings riak]]
                   (when riak
                     (wc/connect (:endpoint riak))))
   :curator (fnk [[:settings zookeeper curator-retry-policy]]
                 (satellite.riemann.services.curator/curator-service
                  zookeeper curator-retry-policy))
   :leader (fnk [[:settings mesos-master-url]]
                (satellite.riemann.services.leader/leader-service
                 mesos-master-url))
   :whitelist-sync (fnk [curator leader
                         [:settings zk-whitelist-path local-whitelist-path]]
                        (satellite.riemann.services.whitelist/whitelist-sync-service
                         curator zk-whitelist-path
                         local-whitelist-path @(:leader? leader)))
   ;; if you want a riak-conn, do not start until you have it
   :http-service (fnk [[:settings
                        service-host service-port riak zk-whitelist-path
                        whitelist-hostname-pred]
                       curator riak-conn whitelist-sync]
                      (satellite.riemann.services.http/http-service
                       {:syncer (:syncer whitelist-sync)
                        :curator (:curator curator)
                        :riak riak
                        :riak-conn riak-conn
                        :whitelist-hostname-pred whitelist-hostname-pred
                        :zk-whitelist-path zk-whitelist-path}
                       service-host
                       service-port))
   :riemann-core (fnk [curator http-service leader whitelist-sync]
                      (riemann.config/service! curator)
                      (riemann.config/service! http-service)
                      (riemann.config/service! leader)
                      (riemann.config/service! whitelist-sync)
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
  ((graph/eager-compile (app settings)) {}))

(comment
  (init-logging)
  (def inst ((graph/eager-compile (app settings)) {}))
  (@(:cli-server inst))

  (require 'riemann.core)
  (require 'riemann.transport)
  (require 'riemann.config)

  (satellite.riemann/reload!)
  )
