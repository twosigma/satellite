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

(require 'satellite-slave.mesos.recipes)

(def mesos-work-dir "/tmp/mesos")

(def settings
  {:satellites [{:host "satellite.master.1.example.com"}
                {:host "satellite.master.2.example.com"}]
   :service "mesos/slave/"
   :comets [(satellite-slave.recipes/free-memory 50 (-> 60 t/seconds))
            (satellite-slave.recipes/free-swap   50 (-> 60 t/seconds))
            (satellite-slave.recipes/percentage-used 90 "/tmp" (-> 60 t/seconds))
            (satellite-slave.recipes/percentage-used 90 "/var" (-> 60 t/seconds))
            (satellite-slave.recipes/percentage-used 90 mesos-work-dir
                                                     (-> 60 t/seconds))
            (satellite-slave.recipes/num-uninterruptable-processes 10 (-> 60 t/seconds))
            (satellite-slave.recipes/load-average 30 (-> 60 t/seconds))

            (satellite-slave.mesos.recipes/total-tasks-failed (-> 60 t/seconds) settings)
            (satellite-slave.mesos.recipes/total-tasks-finished (-> 60 t/seconds) settings)
            (satellite-slave.mesos.recipes/total-tasks-started (-> 60 t/seconds) settings)

            {:command ["echo" "17"]
             :schedule (every (-> 60 t/seconds))
             :output (fn [{:keys [out err exit]}]
                       (let [v (-> out
                                   (clojure.string/split #"\s+")
                                   first
                                   (Integer/parseInt))]
                         [{:state "ok"
                           :metric v
                           :ttl 300
                           :description "example test -- number of files/dirs in cwd"}]))}]})
