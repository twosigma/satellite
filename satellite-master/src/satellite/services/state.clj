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
