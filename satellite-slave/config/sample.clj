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

(def custom-comets
  [{:command ["echo" "17"]
    :schedule (every (-> 60 t/seconds))
    :output (fn [{:keys [out err exit]}]
              (let [v (-> out
                          (clojure.string/split #"\s+")
                          first
                          (Integer/parseInt))]
                [{:state "ok"
                  :metric v
                  :ttl 300
                  :description "example test -- number of files/dirs in cwd"}]))}])

(def settings
  (-> "config/sample-settings.json"
      clojure.java.io/reader
      (cheshire/parse-stream true)
      enrich-settings))
