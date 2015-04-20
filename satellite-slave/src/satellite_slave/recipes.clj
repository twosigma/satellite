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

(defn make-recipe-cmd
  [& xs]
  (str "/home/tsram/satellite_slave" (java.io.File/separator) "satellite-recipes " (clojure.string/join " " xs)))

(defn free-memory
  [threshold period]
  {:riemann {:ttl (* 5 (.getSeconds period))
             :service "free memory in MB"}
   :test {:command (make-recipe-cmd "free-memory")
          :schedule (every period)
          :output {:out (fn [out]
                          (let [v (Integer/parseInt (clojure.string/trim out))]
                            [(> v threshold) v]))}
          :timeout 5}})

(defn free-swap
  [threshold period]
  {:riemann {:ttl (* 5 (.getSeconds period))
             :service "free swap in MB"}
   :test {:command (make-recipe-cmd "free-swap")
          :schedule (every period)
          :timeout 5
          :output {:out (fn [out]
                          (let [v (Integer/parseInt (clojure.string/trim out))]
                            [(> v threshold) v]))}}})

(defn free-swap-iff-swap
  [threshold period]
  {:riemann {:ttl (* 5 (.getSeconds period))
             :service "free swap iff swap in MB"}
   :test {:command (make-recipe-cmd "swap-info")
          :schedule (every period)
          :timeout 5
          :output {:out (fn [out]
                          (let [[configured used free] (map (fn [o] (Integer/parseInt (clojure.string/trim o))) (clojure.string/split out #"\s+"))]
                            [(or (= configured 0) (> free threshold)) free]))}}})

(defn percentage-used
  [threshold path period]
  {:riemann {:ttl (* 5 (.getSeconds period))
             :service (str "percentage used of " path)}
   :test {:command (make-recipe-cmd "percentage-used" path)
          :schedule (every period)
          :output {:out (fn [out]
                          (let [v (Integer/parseInt (clojure.string/trim out))]
                            [(< v threshold) v]))}
          :timeout 5}})

(defn df-returns
  [timeout period]
  {:riemann {:ttl (* 5 (.getSeconds period))
             :service "df returns in timely fashion"}
   :test {:command "/bin/df"
          :schedule (every period)
          :output {:exit identity}
          :timeout timeout}})

(defn num-uninterruptable-processes
  [threshold period]
  {:riemann {:ttl (* 5 (.getSeconds period))
             :service "number of processes in uninterruptable sleep"}
   :test {:command (make-recipe-cmd "num-uninterruptable-processes")
          :schedule (every period)
          :output {:out (fn [out]
                          (let [v (Integer/parseInt (clojure.string/trim out))]
                            [(< v threshold) v]))}}})

(defn load-average
  [threshold period]
  {:riemann {:ttl (* 5 (.getSeconds period))
             :service "load average over past 15 minutes"}
   :test {:command (make-recipe-cmd "load-average")
          :schedule (every period)
          :output {:out (fn [out]
                          (let [v (Float/parseFloat (clojure.string/trim out))]
                            [(< v threshold) v]))}}})

(defn file-exists
  [path period]
  {:riemann {:ttl (* 5 (.getSeconds period))
             :service (str path "exists")}
   :test {:command (make-recipe-cmd "file-exists" path)
          :schedule (every period)
          :output {:exit identity}}})
