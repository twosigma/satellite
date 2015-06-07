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

;; quick helper time functions, unit of time is milliseconds
(ns satellite.time
  (import java.util.concurrent.TimeUnit))

(defn millis
  [ms]
  ms)

(defn seconds
  [secs]
  (* 1000 secs))

(defn minutes
  [mins]
  (* mins (seconds 60)))

(defn hours
  [hrs]
  (* hrs (minutes 60)))

(defn unix-now
  []
  (.toSeconds TimeUnit/MILLISECONDS
              (System/currentTimeMillis)))
