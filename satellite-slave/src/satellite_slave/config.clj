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

(ns satellite-slave.config
  (:require [clj-time.periodic :refer [periodic-seq]]
            [clj-time.core :as t]
            [satellite-slave.recipes]
            [satellite-slave.util :refer [every]]))

(def settings
  {:satellites [{:host "localhost"}]
   ;; a comet has the following schema:
   ;;
   ;; comet : {:riemann Riemann-map :test Test-map}
   ;;
   ;; where Riemann-map is a typical Riemann map with the following default
   ;; values:
   ;;     hostname: will be resolved by hostname(1)
   ;;     time: will be approximately time command was run.
   ;;
   ;; and Test-map has the following schema
   ;;
   ;; Test-map : {:command String | list of Strings, required, a shell command
   ;;                      to run, e.g., "ls -l" or ["ls" "-l"]
   ;;             :schedule required, sequence of joda times, when to call your
   ;;                       test
   ;;             :output  Output-map, optional, expectations of the output
   ;;             :timeout long, optional, timeout for shell command in seconds
   ;;             :eventify, fn, optional, if you want override post-processing}.
   ;;
   ;; The Output-map schema is
   ;;
   ;; Output-map : {:out Output-val, optional
   ;;               :exit Output-val, optional
   ;;               :err Output-val, optional}.
   ;;
   ;; The Output-val schema is
   ;;
   ;; Output-val : (fn x -> Any) | x
   ;;
   ;; which is to say, either a unary function or a value. If it is function, it
   ;; is applied to the command output for the corresponding key. If it is a
   ;; value, the command output is tested for equality against it.
   :comets [{:riemann {:hostname "dope.host"
                       :ttl 60}
             :test {:command "echo Hello"
                    :output {:out "Hello\n"}
                    :timeout 30
                    :schedule (every (-> 3 t/seconds))}}
            {:riemann {:ttl 40
                       :tags ["pink" "pig"]}
             :test {:command ["ls" "-l"]
                    :schedule (every (-> 10 t/seconds))}}
            {:riemann {:ttl 20
                       :tags ["yellow" "pig"]}
             :test {:command ["ls" "-l"]
                    :output {:exit (fn [ret] (+ ret 17))}
                    :schedule (every (-> 17 t/seconds))}}]
   :safe-env true})

(defn include
  [config]
  (binding [*ns* (find-ns 'satellite-slave.config)]
    (load-file config)))
