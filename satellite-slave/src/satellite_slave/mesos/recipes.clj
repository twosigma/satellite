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
            [clojure.tools.logging :as log]
            [satellite-slave.mesos.cache :as cache]
            [satellite-slave.util :refer [every]]))

(defn cache-state
  "Return a map which could be used as input for core/run-test.

   Args:
       threshold: Long, specifies the how many failures would trigger a riemann event with critical state.
       period: Seconds, specifies the period this task / test expected to run.
       conifg: Map, specifies the config of this task / test which is expected to have keys :slave (optional), :riak-url, and :bucket.
   Output:
       Return a map which could be used as input for core/run-test."
  [threshold period config]
  (let [local-host (.getCanonicalHostName (java.net.InetAddress/getLocalHost))
        slave-host (get config :slave local-host)]
    {:command ["echo" slave-host]
     :schedule (every period)
     :output (fn [& params]
               (let [{riak-url :riak-url
                      bucket   :bucket} config
                     failures (try
                                (cache/cache-state-and-count-failures slave-host riak-url bucket)
                                (catch Exception e
                                  (log/error e "Failed to cache state.")
                                  ;; Return a value strictly larger that the threshold.
                                  (inc threshold)))]
                 {:ttl (* 5 (.getSeconds period))
                  :service "cache state"
                  :state (if (> failures threshold) "critical" "ok")
                  :metric failures}))}))
