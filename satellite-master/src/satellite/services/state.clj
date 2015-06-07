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

(ns satellite.services.state
  (:require [clojurewerkz.welle.kv :as kv]
            [liberator.core :refer (resource)]))

(defn tasks-metadata
  [riak-conn bucket]
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]
   :malformed? (fn [ctx]
                 (if-let [task (get-in ctx [:request :params "task"])]
                   [false {::task task}]
                   [true {::error "task query-parameter is required."}]))
   :exists? (fn [ctx]
              (let [res (kv/fetch riak-conn bucket (::task ctx))]
                (if (kv/has-value? res)
                  [true {::res (:value (first (:result res)))}]
                  [false {::error "Did not find the task-id you requested."}])))
   :handle-malformed (fn [ctx]
                       (::error ctx))
   :handle-not-found (fn [ctx]
                       (::error ctx))
   :handle-ok (fn [ctx]
                (::res ctx))))
