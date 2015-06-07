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

(ns satellite.riemann.services.stats
  (:require [riemann.config]))

(defrecord StatsHTTPService
           [core stats]
  ServiceEquiv
  (equiv? [this other]
    (instance? StatsHTTPService other))
  Service
  (conflict? [this other]
    (instance? StatsHTTPService other))
  (reload! [this new-core]
    (reset! core new-core))
  (start! [this]
    (locking this
      (when-not @stats
        (reset! stats {})
        (let [event-put (fn [event]
                          (if (riemann.config/expired? event)
                            (swap! stats dissoc
                                   (:service event))
                            (swap! stats assoc
                                   (:service event)
                                   (:metric event))))]
          (intern 'riemann.config
                  'stats-put*
                  (fn [k v] (swap! stats assoc k v)))
          (intern 'riemann.config
                  'stats-get*
                  (fn [k] (get @stats k)))
          (intern 'riemann.config
                  'stats-put
                  event-put)))))
  (stop! [this]
    (locking this)))

(defn stats-service
  []
  (StatsHTTPService. (atom nil) (atom nil)))
