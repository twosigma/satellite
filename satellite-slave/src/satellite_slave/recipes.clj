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

(ns satellite-slave.recipes
  (:require [clj-time.periodic :refer [periodic-seq]]
            [clj-time.core :as t]
            [satellite-slave.util :refer [every]]))


(defn free-memory
  [period {:keys [threshold]}]
  {:command ["satellite-recipes" "free-memory"]
   :schedule (every period)
   :output (fn [{:keys [out err exit]}]
             (let [v (Integer/parseInt (clojure.string/trim out))]
               [{:ttl (* 5 (.getSeconds period))
                 :service "free memory in MB"
                 :state (if (> v threshold) "ok" "critical")
                 :metric v}]))
   :timeout 5})

(defn free-swap
  [period {:keys [threshold]}]
  {:command ["satellite-recipes" "free-swap"]
   :schedule (every period)
   :timeout 5
   :output (fn [{:keys [out err exit]}]
             (let [v (Integer/parseInt (clojure.string/trim out))]
               [{:ttl (* 5 (.getSeconds period))
                 :service "free swap in MB"
                 :state (if (> v threshold) "ok" "critical")
                 :metric v}]))})

(defn free-swap-iff-swap
  [period {:keys [threshold]}]
  {:command ["satellite-recipes" "swap-info"]
   :schedule (every period)
   :timeout 5
   :output (fn [{:keys [out err exit]}]
             (let [->int (fn [o]
                           (Integer/parseInt (clojure.string/trim o)))
                   [configured used free]
                   (map ->int (clojure.string/split out #"\s+"))]
               [{:ttl (* 5 (.getSeconds period))
                 :service "free swap iff swap in MB"
                 :state (if (or (= configured 0) (> free threshold))
                          "ok" "critical")
                 :metric free}]))})

(defn percentage-used
  [period {:keys [threshold path]}]
  {:command ["satellite-recipes" "percentage-used" path]
   :schedule (every period)
   :timeout 5
   :output (fn [{:keys [out err exit]}]
             (let [v (Integer/parseInt (clojure.string/trim out))]
               [{:ttl (* 5 (.getSeconds period))
                 :service (str "percentage used of " path)
                 :state (if (< v threshold) "ok" "critical")
                 :metric v}]))})

(defn df-returns
  [period {:keys [timeout]}]
  {:command "/bin/df"
   :schedule (every period)
   :timeout timeout
   :output (fn [{:keys [out exit err]}]
             [{:ttl (* 5 (.getSeconds period))
               :service "df returns in timely fashion"
               :state (if (zero? exit) "ok" "critical")
               :metric exit}])})

(defn num-uninterruptable-processes
  [period {:keys [threshold]}]
  {:command ["satellite-recipes" "num-uninterruptable-processes"]
   :schedule (every period)
   :output (fn [{:keys [out err exit]}]
             (let [v (Integer/parseInt (clojure.string/trim out))]
               [{:ttl (* 5 (.getSeconds period))
                 :service "number of processes in uninterruptable sleep"
                 :state (if (< v threshold) "ok" "critical")
                 :metric v}]))})

(defn load-average
  [period {:keys [threshold]}]
  {:command ["satellite-recipes" "load-average"]
   :schedule (every period)
   :output (fn [{:keys [out err exit]}]
             (let [v (Float/parseFloat (clojure.string/trim out))]
               [{:ttl (* 5 (.getSeconds period))
                 :service "load average over past 15 minutes"
                 :state (if (< v threshold) "ok" "critical")
                 :metric v}]))})

(defn file-exists
  [period {:keys [path]}]
  :riemann {}
  {:command ["satellite-recipes" "file-exists" path]
   :schedule (every period)
   :output (fn [{:keys [out err exit]}]
             [{:state (if (zero? exit) "ok" "critical")
               :metric exit
               :ttl (* 5 (.getSeconds period))
               :service (str path "exists")}])})
