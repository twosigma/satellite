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

(ns satellite-slave.relay
  (:require
   [clj-http.client :as http]
   [cheshire.core :as json]
   [satellite-slave.config :as config]))

;; type state = Map (Any, state) | List state

(defn state-exception
  [x]
  (ex-info "not type state" {:arg x}))

(defn update-state
  "Like Update-in, but for state!

   Updates a values in a state, where kks is a sequence of keys and f is a
   function that will take the value associated with kks and be applied to that.

   This is different than update-in because type `state` can be a List as well.
   In this case, we recur: map over the list, applying update-state.

   Args:
       x: collection, type state
       kks: sequence of keys
       f: function to apply

   Output:
       updated state map, type state"
  [x [k & ks :as kks] f]
  (cond
    (nil? k) x
    (map? x) (if (seq ks)
               (assoc x k (update-state (get x k) ks f))
               (assoc x k (f (get x k))))
    (sequential? x) (map #(update-state % kks f) x)
    :else (throw (state-exception x))))

(defn filter-state
  "

   Args:
       x: collection, type state
       kks: sequence of keys
       f: function to apply

   Output:
       updated state map, type state"
  [x [k & ks :as kks] f]
  (cond
    (nil? k) x
    (map? x) (if (seq ks)
               (assoc x k (filter-state (get x k) ks f))
               (assoc x k (f (get x k))))
    (sequential? x) (if (seq ks)
                      (map #(filter-state % kks f) x)
                      (filter #(f (get % k)) x))
    :else (throw (state-exception x))))

(defn filter-tasks-from-state
  "Filter state to contain only the tasks we care about.

   Args:
       tasks: set of string task-ids
       state: state

   Output:
       state map with tasks limited to those in tasks set"
  [tasks state]
  (let [combos (for [f ["frameworks" "completed_frameworks"]
                     e ["executors" "completed_executors"]
                     t ["tasks" "completed_tasks"]]
                 [f e t])
        filtered (loop [[[f-key e-key t-key] & fes] combos
                        state state]
                   (if f-key
                     (recur fes
                            (filter-state state
                                          [f-key e-key t-key "id"]
                                          tasks))
                     state))]
    filtered))

(defn prune-state
  "Recursively delete dead state. kkks is a sequence of sequences of keys, e.g.
   [[:a :b] [:c] [:d :e :f]], which are traversed in state to see if they are
   all empty, and if so, remove this node. In this case, if [:d :e :f] for each
   map in the :c list for :a, then this node is deleted, irrespective of the
   other values/keys in the peer levels of :c and [:d :e :f], so in our final
   output, the root will contain :a (provided :b has some not empty leaf), but
   :a will point to an empty list.

   Args:
       x: state map
       kkks: sequence of sequences of keys

   Output:
       state map"
  [x [ks & kks :as kkks]]
  (cond
    (nil? ks) nil
    (map? x) (let [x (if (seq kks)
                       (loop [[k & ks] ks
                              x x]
                         (if k
                           (recur ks
                                  (assoc x k (prune-state (get x k) kks)))
                           x))
                       x)]
               (when-not (every? empty? (map x ks))
                 x))
    (sequential? x) (filter identity
                            (map #(prune-state % kkks) x))
    :else (throw (state-exception x))))

(defn prune-tasks-from-state
  [x]
  (prune-state x [["frameworks" "completed_frameworks"]
                  ["executors" "completed_executors"]
                  ["tasks" "completed_tasks"]]))

(defn state->minimal-task-state
  "Return a state map with only the tasks specified.

   Args:
       tasks: set of task-ids
       s: state

   Output:
       state map with all tasks not in tasks removed"
  [tasks s]
  (-> (filter-tasks-from-state tasks s)
      prune-tasks-from-state))

(defn get-state
  "Retrieve mesos state as a map from a given slave host. If any exception e
   happens, it will simply return a map {:error e}."
  ([]
   (get-state (:slave-host config/settings)))
  ([host]
   (get-state ^String host "http"))
  ([^String host ^String scheme]
   (try
     (http/get (format "%s://%s:5051/state.json" scheme host)
               {:socket-timeout (* 20 1000) ;; in milliseconds
                :conn-timeout (* 20 1000)}) ;; in milliseconds
     (catch Exception e
       {:error e}))))


(defn get-observability-metrics
  "Retrieve the observability metrics
  http://mesos.apache.org/documentation/latest/monitoring/"
  ([]
   (get-observability-metrics (:slave-host config/settings)))
  ([host]
   (get-observability-metrics ^String host "http"))
  ([^String host ^String scheme]
  (try
    (http/get (format "%s://%s:5051/metrics/snapshot" scheme host)
              {:socket-timeout (* 20 1000) ;; in milliseconds
               :conn-timeout (* 20 1000)}) ;; in milliseconds
    (catch Exception e
      {:error e}))))

