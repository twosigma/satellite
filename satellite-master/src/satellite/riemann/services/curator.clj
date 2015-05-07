(ns satellite.riemann.services.curator
  (:require [clojure.tools.logging :as log]
            [riemann.service :refer (Service ServiceEquiv)]
            [satellite.time :as time])
  (:import (org.apache.curator.framework CuratorFrameworkFactory)
           (org.apache.curator.retry.BoundedExponentialBackoffRetry)))

(defrecord CuratorService
           [zookeeper curator-retry-policy curator core]
  ServiceEquiv
  (equiv? [this other]
    (and (instance? CuratorService other)
         (= zookeeper (:zookeeper other))
         (= curator-retry-policy (:curator-retry-policy other))))
  Service
  (conflict? [this other]
    (and (instance? CuratorService other)
         (= zookeeper (:zookeeper other))
         (= curator-retry-policy (:curator-retry-policy other))))
  (reload! [this new-core]
    (reset! core new-core))
  (start! [this]
    (locking this
      (when-not (realized? curator)
        (let [session-timeout (-> 3 time/minutes)
              connection-timeout (-> 30 time/seconds)
              curator-retry-policy (org.apache.curator.retry.BoundedExponentialBackoffRetry.
                                    (:base-sleep-time-ms curator-retry-policy)
                                    (:max-sleep-time-ms curator-retry-policy)
                                    (:max-sleep-time-ms curator-retry-policy))
              client (CuratorFrameworkFactory/newClient
                      zookeeper session-timeout connection-timeout
                      curator-retry-policy)]
          (.. client start)
          (deliver curator client)))))
  (stop! [this]
    (locking this
      (.close @curator))))

(defn curator-service
  [zookeeper curator-retry-policy]
  (CuratorService. zookeeper curator-retry-policy (promise) (atom nil)))
