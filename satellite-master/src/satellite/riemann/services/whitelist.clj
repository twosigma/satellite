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

(ns satellite.riemann.services.whitelist
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [riemann.service :refer (Service ServiceEquiv)]
            [clj-time.core :as t]
            [satellite.whitelist :as whitelist]))

(defrecord WhitelistSyncService
           [curator zk-whitelist-path local-whitelist-path initial-local-whitelist-path
            leader? core syncer]
  ServiceEquiv
  (equiv? [this other]
    (and (instance? WhitelistSyncService other)
         (= curator (:curator other))
         (= zk-whitelist-path (:zk-whitelist-path other))
         (= local-whitelist-path (:local-whitelist-path other))
         (= initial-local-whitelist-path (:initial-local-whitelist-path other))
         (= leader? (:leader? other))))
  Service
  (conflict? [this other]
    (and (instance? WhitelistSyncService other)
         (= curator (:curator other))
         (= zk-whitelist-path (:zk-whitelist-path other))
         (= local-whitelist-path (:local-whitelist-path other))
         (= initial-local-whitelist-path (:initial-local-whitelist-path other))
         (= leader? (:leader? other))))
  (reload! [this new-core]
    (reset! core new-core))
  (start! [this]
    (log/info "Starting whitelist service")
    (try
      (locking this
        (when-not (realized? syncer)
          ;; if the root Zookeeper whitelist node doesn't exist
          ;; and you are leader, push the current whitelist to
          ;; zookeeper
          (let [curator @(:curator curator)]
            (when (and (not (.. curator checkExists (forPath zk-whitelist-path)))
                       (@leader?))
              (.. curator create (forPath zk-whitelist-path)))
            (let [sync-ch (async/chan (async/sliding-buffer 1))
                  cache (whitelist/start-cache! curator zk-whitelist-path sync-ch)

                  sync-period (t/seconds 10)
                  shutdown-syncer (whitelist/start-batch-sync! curator
                                                               cache
                                                               zk-whitelist-path
                                                               sync-ch
                                                               sync-period
                                                               (fn [cache]
                                                                 (with-open [wtr (clojure.java.io/writer
                                                                                  local-whitelist-path)]
                                                                   (whitelist/write-out-cache!
                                                                    wtr
                                                                    cache))))

                  shutdown-reaper (whitelist/start-reaper! curator
                                                           cache
                                                           zk-whitelist-path)]
              (deliver syncer {:cache cache :sync shutdown-syncer :reaper shutdown-reaper})))))
      (catch Throwable e
        (log/error e "Failed to start whitelist service")))
    (log/info "Whitelist service started"))
  (stop! [this]
    (locking this
      (.close (:cache @syncer))
      ((:sync @syncer))
      ((:reaper @syncer)))))

(defn whitelist-sync-service
  [curator zk-whitelist-path local-whitelist-path initial-local-whitelist-path
   leader?]
  (->WhitelistSyncService curator zk-whitelist-path local-whitelist-path
                         initial-local-whitelist-path leader?
                         (atom nil) (promise)))
