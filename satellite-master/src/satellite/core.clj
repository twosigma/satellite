;; Copyright 2015 TWO SIGMA OPEN SOURCE, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

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
   :zookeeper-root (s/both s/Str (s/pred #(not (#{"","/"} %)) 'valid-zookeeper-root?))
   :curator-retry-policy {:base-sleep-time-ms s/Int
                          :max-sleep-time-ms s/Int
                          :max-retries s/Int}
   :local-whitelist-path s/Str
   :whitelist-hostname-pred (s/pred clojure.test/function? 'clojure.test/function)})

(def settings
  ;; Settings for the Satellite monitor/service
  ;;
  ;; Current values are used as the defaults; to override them, assoc values
  ;; into this var
  {;; the Riemann config file that will process events
   :riemann-config "config/riemann-config.clj"
   ;; if you do not explicitly bind you will just get localhost
   :riemann-tcp-server-options {:host "127.0.0.1"}
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
   :service-host "127.0.0.1"
   ;; Port on which the service is publicly accessible
   :service-port 5001
   ;; Zookeeper string used for whitelist co-ordination
   :zookeeper "zk1:port,zk2:port,zk3:port"
   ;; Root dir in zookeeper to store satellite state
   :zookeeper-root "/satellite"
   ;; Curator retry policy
   :curator-retry-policy {:base-sleep-time-ms 100
                          :max-sleep-time-ms 120000
                          :max-retries 10}
   ;; the path on disk to the Mesos whitelist
   :local-whitelist-path "whitelist"
   ;; predicate used to validate hosts that are added to the whitelist
   :whitelist-hostname-pred (fn [hostname]
                              (identity hostname))})

(defn map->graph
  [m]
  (plumbing.core/map-vals (fn [x] (fnk [] x)) m))

(defn app
  [settings]
  {:settings settings
   :zk-whitelist-path (fnk [[:settings zookeeper-root]]
                           (str zookeeper-root "/whitelist"))
   :riak-conn (fnk [[:settings riak]]
                   (when riak
                     (wc/connect (:endpoint riak))))
   :curator (fnk [[:settings zookeeper zookeeper-root curator-retry-policy]]
                 (riemann.config/service!
                  (satellite.riemann.services.curator/curator-service
                   zookeeper zookeeper-root curator-retry-policy)))
   :leader (fnk [[:settings mesos-master-url]]
                (riemann.config/service!
                 (satellite.riemann.services.leader/leader-service
                  mesos-master-url)))
   ;; the path on Zookeeper to the whitelist coordination node
   :whitelist-sync (fnk [curator leader [:settings local-whitelist-path] zk-whitelist-path]
                        (let [whitelist-sync (satellite.riemann.services.whitelist/whitelist-sync-service
                                              curator zk-whitelist-path
                                              local-whitelist-path local-whitelist-path
                                              (:leader? leader))]
                          (future
                            (intern 'satellite.recipes
                                    'on-host
                                    (fn [host]
                                      (whitelist/on-host
                                       @(:curator curator)
                                       (:cache @(:syncer whitelist-sync))
                                       zk-whitelist-path
                                       host)))
                            (intern 'satellite.recipes
                                    'off-host
                                    (fn [host]
                                      (whitelist/off-host
                                       @(:curator curator)
                                       (:cache @(:syncer whitelist-sync))
                                       zk-whitelist-path
                                       host)))
                            (intern 'satellite.recipes
                                    'persist-event
                                    (fn [event]
                                      (whitelist/persist-event
                                       @(:curator curator)
                                       (:cache @(:syncer whitelist-sync))
                                       zk-whitelist-path
                                       (:host event)
                                       (:service event)
                                       event)))
                            (intern 'satellite.recipes
                                    'delete-event
                                    (fn [event]
                                      (whitelist/delete-event
                                       @(:curator curator)
                                       (:cache @(:syncer whitelist-sync))
                                       zk-whitelist-path
                                       (:host event)
                                       (:service event)))))
                          (riemann.config/service! whitelist-sync)))
   ;; if you want a riak-conn, do not start until you have it
   :http-service (fnk [[:settings
                        service-host service-port riak
                        whitelist-hostname-pred]
                       zk-whitelist-path
                       curator riak-conn whitelist-sync]
                      (riemann.config/service!
                       (satellite.riemann.services.http/http-service
                        {:syncer (:syncer whitelist-sync)
                         :curator (:curator curator)
                         :riak riak
                         :riak-conn riak-conn
                         :whitelist-hostname-pred whitelist-hostname-pred
                         :zk-whitelist-path zk-whitelist-path}
                        service-host
                        service-port)))
   :riemann-core (fnk [curator http-service leader whitelist-sync]
                      riemann.config/core)
   :riemann (fnk [[:settings riemann-config] riemann-core]
                 (try
                   (intern 'riemann.config
                           'leader?
                           (fn [] nil))
                   (riemann/start-riemann riemann-config)
                   (catch Throwable t
                     (log/error "Riemann failed")
                     (throw t))))
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
        (def settings (merge settings (load-file config))))
    (log/info (str "Using default settings" settings)))
  (s/validate settings-schema settings)
  ((graph/eager-compile (app (map->graph settings))) {}))

(comment
  (init-logging)
  (def inst (-main "config/dev/satellite-config.clj"))
  (@(:cli-server inst))

  (require 'riemann.core)
  (require 'riemann.transport)
  (require 'riemann.config)

  (satellite.riemann/reload!))
