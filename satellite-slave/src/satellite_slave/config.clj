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
  (:require [cheshire.core :as cheshire]
            [clj-time.periodic :refer [periodic-seq]]
            [clj-time.core :as t]
            [schema.core :as s]
            [satellite-slave.recipes]
            [satellite-slave.util :refer [every]])
  (:import (org.joda.time Period)))

(defn joda-sequence?
  "Returns true iff the first 3 elements of the input are joda times.
  We need to restrict to a sample in order to validate this as best we can
  in order to catch common errors, since it's unwise to examine every item
  of an infinite sequence! "
  [input]
  (every? #(instance? org.joda.time.DateTime %) (doall (take 3 input))))

(def settings-schema
  {:satellites [{s/Any s/Any}]
   :service s/Str
   :comets [{:command (s/either s/Str [s/Str])
             :schedule (s/pred joda-sequence?)
             (s/optional-key :timeout) s/Int
             :output (s/pred clojure.test/function? 'clojure.test/function)}]
   (s/optional-key :safe-env) (s/either s/Bool {s/Any s/Any})})

(def settings
  {;; :satellite : [riemann-tcp-client]; configure one client per
   ;;              satellite-master. See
   ;;              https://github.com/aphyr/riemann-clojure-client/blob/0.3.1/src/riemann/client.clj#L121
   ;;              for parameters
   :satellites [{:host "localhost"}]
   ;; :service : String, to prefix the :service key in all Riemann events
   :service "/my/yellow/pig/service"
   ;; :comets : [comet], where a comet has the following schema:
   ;;
   ;; comet : {:command     String | [String]
   ;;          :schedule    [joda times]
   ;;          :timeout     Integer
   ;;          :output      output-fn}
   ;;
   ;; :command  : a shell command to run; e.g., "ls -l" or ["ls" "-l"]
   ;; :schedule : when to call your test
   ;; :timeout  : how many seconds to wait for shell command to complete
   ;;             before returning :timeout "critical" state
   ;; :output   : a function that takes a map with three arguments--
   ;;             out, err, exit--and returns a list of Riemann event maps
   ;;             with the following default values:
   ;;
   ;;               host: will be resolved by hostname(1)
   ;;               time: will be approximately time command was run.
   ;;
   ;;             Each of these event maps will be sent to each
   ;;             satellite-master specified in :satellites.
   :comets [{:command "echo Hello"
             :schedule (every (-> 3 t/seconds))
             :timeout 30
             :output (fn [{:keys [out exit err]}]
                       [{:ttl 30
                         :service "Hello service"
                         :state "ok"}])}
            {:command ["ls" "-l"]
             :schedule (every (-> 10 t/seconds))
             :timeout 5
             :output (fn [{:keys [out exit err]}]
                       [{:ttl 30
                         :service "ls -l"
                         :state (if (zero? exit) "ok" "critical")}])}
            {:command ["ls" "-l"]
             :schedule (every (-> 17 t/seconds))
             :output (fn [{:keys [out exit err]}]
                       [{:ttl 20
                         :service "yellow pig increment"
                         :state "ok"
                         :metric (+ exit 17)
                         :tags ["yellow" "pig"]}])}]
   ;; :safe-env : bool | {String String} , whether you want to clear the
   ;;             environment variables or wish to specify what environment
   ;;             Satellite should have.
   :safe-env true})

(defn build-comet
  "Input: a simplified static data structure referring to predefined
  comet-generating function (see example-settings.json).
  Output: a full-fledged comet."
  [c]
  ((resolve (symbol (:name c)))
   ;; https://en.wikipedia.org/wiki/ISO_8601#Durations
   (Period/parse (str "PT" (:period c)))
   (:params c)))

(defn include
  [config]
  (binding [*ns* (find-ns 'satellite-slave.config)]
    (load-file config)))

(defn validate-current-settings!
  []
  (s/validate settings-schema settings))

