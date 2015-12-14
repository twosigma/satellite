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

(ns satellite-slave.mesos.monitoring
  (:require [satellite-slave.relay :as relay]
            [cheshire.core :as json]))

(defn parse-observability-metrics
  "Retrieves the observability metrics and parses them.
  Throws an exception if the metrics can't be retrieved or parsed."
  [slave-host]
  (let [{:keys [body error]} (relay/get-observability-metrics slave-host)]
    (if error
      (throw (ex-info (str "Failed to get metrics from host" slave-host) {:error error}))
      (json/parse-string body))))

(defn num-tasks-failed
  [metrics]
  (-> "slave/tasks_failed" metrics int))

(defn num-tasks-finished
  [metrics]
  (-> "slave/tasks_finished" metrics int))

(defn num-tasks-started
  "A sum of the number of tasks in any state."
  [metrics]
  (let [task-states #{"failed" "finished" "killed" "lost"
                      "running" "staging" "starting"}]
    (apply + (map #(metrics (str "slave/tasks_" %)) task-states))))
