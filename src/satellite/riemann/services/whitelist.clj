(ns satellite.riemann.services.whitelist
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [riemann.service :refer (Service ServiceEquiv)]
            [satellite.time :as time]
            [satellite.whitelist :as whitelist]))

(defrecord WhitelistSyncService
    [curator zk-whitelist-path local-whitelist-path leader? core syncer]
  ServiceEquiv
  (equiv? [this other]
    (and (instance? WhitelistSyncService other)
         (= curator (:curator other))
         (= zk-whitelist-path (:zk-whitelist-path other))
         (= local-whitelist-path (:local-whitelist-path other))
         (= leader? (:leader? other))))
  Service
  (conflict? [this other]
    (and (instance? WhitelistSyncService other)
         (= curator (:curator other))
         (= zk-whitelist-path (:zk-whitelist-path other))
         (= local-whitelist-path (:local-whitelist-path other))
         (= leader? (:leader? other))))
  (reload! [this new-core]
    (reset! core new-core))
  (start! [this]
    (locking this
      (when-not (realized? syncer)
        ;; if the root Zookeeper whitelist node doesn't exist
        ;; and you are leader, push the current whitelist to
        ;; zookeeper
        (let [curator @(:curator curator)]
          (when (and (not (.. curator checkExists (forPath zk-whitelist-path)))
                     (leader?))
            (whitelist/initialize-whitelist
             (clojure.java.io/reader local-whitelist-path)
             curator
             zk-whitelist-path))
          (let [batch-every (-> 10 time/seconds)
                batch-syncer (whitelist/batch-sync curator
                                                   zk-whitelist-path
                                                   batch-every
                                                   (fn [cache]
                                                     (with-open [wtr (clojure.java.io/writer
                                                                      local-whitelist-path)]
                                                       (whitelist/write-out-cache!
                                                        wtr
                                                        cache))))]
            (deliver syncer batch-syncer)
            (intern 'satellite.recipes
                    'on-host
                    (fn [host]
                      (whitelist/on-host
                       (:cache batch-syncer)
                       curator
                       zk-whitelist-path
                       host)))
            (intern 'satellite.recipes
                    'off-host
                    (fn [host]
                      (whitelist/off-host
                       (:cache batch-syncer)
                       curator
                       zk-whitelist-path
                       host))))))))
  (stop! [this]
    (locking this
      (.close (:cache @syncer))
      (async/close! (:sync @syncer)))))

(defn whitelist-sync-service
  [curator zk-whitelist-path local-whitelist-path leader?]
  (WhitelistSyncService. curator zk-whitelist-path local-whitelist-path leader?
                         (atom nil) (promise)))
