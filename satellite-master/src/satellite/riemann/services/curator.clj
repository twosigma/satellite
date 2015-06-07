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
