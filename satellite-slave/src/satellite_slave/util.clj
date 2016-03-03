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

(ns satellite-slave.util
  (:require [clj-time.periodic :refer [periodic-seq]]
            [clj-time.core :as t]))

(defn every
  "Create an infinite lazy-seq of times, s, where
  s_0 = t milliseconds from now
  s_i = s_{i-1} + t milliseconds

  Arguments:
      t: Joda-Time"
  [t]
  (periodic-seq (t/now) t))

(defn get-slave-host
  [config]
  (get config :slave-host (.getCanonicalHostName (java.net.InetAddress/getLocalHost)))
)
