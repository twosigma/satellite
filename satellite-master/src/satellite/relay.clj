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

(ns satellite.relay)

;; type state = Map (Any, state) | List state

(defn state-exception
  [x]
  (ex-info "not type state" {:arg x}))

(defn get-in-state
  "Like get-in, but for state!

  Args:
      x: collection, type state
      ks: sequence of keys

  Output:
      sequence"
  [x [k & ks :as kks]]
  (let [helper
        (fn [x [k & ks :as kks]]
          (cond
            (nil? k) x
            (map? x) (if (seq ks)
                       (get-in-state (get x k) ks)
                       (get x k))
            (sequential? x) (map #(get-in-state % kks) x)
            :else (throw (state-exception x))))]
    (flatten (helper x kks))))
