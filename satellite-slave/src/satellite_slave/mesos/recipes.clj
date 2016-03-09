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

(ns satellite-slave.mesos.recipes
  (:require [clj-time.core :as t]
            [satellite-slave.config :refer [settings]]
            [clojure.tools.logging :as log]
            [satellite-slave.mesos.cache :as cache]
            [satellite-slave.mesos.monitoring :as mon]
            [satellite-slave.util :refer [every]]
            [satellite-slave.config :as config]))

(defn cache-state
  "Return a map which could be used as input for core/run-test.

   Args:
       threshold: Long, specifies the how many failures would trigger a riemann event with critical state.
       period: Seconds, specifies the period this task / test expected to run.
       conifg: Map, specifies the config of this task / test which is expected to have keys :slave (optional), :riak-url, and :bucket.
   Output:
       Return a map which could be used as input for core/run-test."
  [threshold period config]
  (let [slave-host (:slave-host config/settings)]
    {:command ["echo" slave-host]
     :schedule (every period)
     :output (fn [& params]
               (let [{riak-url :riak-url
                      bucket   :bucket} config
                      failures (try
                                 (cache/cache-state-and-count-failures slave-host riak-url bucket)
                                 (catch Throwable e
                                   (log/error e "Failed to cache state.")
                                   ;; Return a value strictly larger that the threshold.
                                   (inc threshold)))]
                 {:ttl (* 5 (.getSeconds period))
                  :service "cache state"
                  :state (if (> failures threshold) "critical" "ok")
                  :metric failures}))}))

(defn total-tasks-failed
  [period _]
  {:command ["echo" "mesos tasks failed"]
   :schedule (every period)
   :output (fn [& params]
             (let [v (-> :slave-host
                         config/settings
                         mon/parse-observability-metrics
                         mon/num-tasks-failed)]
               [{:service "total-tasks-failed"
                 :state "ok"
                 :metric v
                 :ttl (* 5 (.getSeconds period))
                 :description "Number of Mesos tasks failed"}
                ]))})

(defn total-tasks-finished
  [period _]
  {:command ["echo" "mesos tasks finished"]
   :schedule (every period)
   :output (fn [& params]
             (let [v (-> :slave-host
                         config/settings
                         mon/parse-observability-metrics
                         mon/num-tasks-finished)]
               [{:service "total-tasks-finished"
                 :state "ok"
                 :metric v
                 :ttl (* 5 (.getSeconds period))
                 :description "Number of Mesos tasks finished"}
                ]))})

(defn total-tasks-started
  [period _]
  {:command ["echo" "mesos tasks started"]
   :schedule (every period)
   :output (fn [& params]
             (let [v (-> :slave-host
                         config/settings
                         mon/parse-observability-metrics
                         mon/num-tasks-started)]
               [{:service "total-tasks-started"
                 :state "ok"
                 :metric v
                 :ttl (* 5 (.getSeconds period))
                 :description "Number of Mesos tasks started"}
                ]))})
