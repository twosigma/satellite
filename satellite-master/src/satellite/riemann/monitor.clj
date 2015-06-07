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

(ns satellite.riemann.monitor
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.tools.logging :as log]
            [riemann.core]
            [satellite.util :as util]
            [satellite.relay :as relay]
            [satellite.services.stats]
            [satellite.time :as time]))

(in-ns 'riemann.config)

(declare leader?)

(in-ns 'satellite.riemann.monitor)


(def hostname (.. java.net.InetAddress getLocalHost getHostName))

(defn get-state-map
  "Retrieve state map from a Mesos Master.

   Arguments:
       mesos-master-url: cemerick.url.URL

   Returns:
       state: map"
  [mesos-master-url]
  (-> (client/get (str mesos-master-url "/state.json")
                  {:as :json-string-keys})
      :body))

(defn state-map->slave-map
  "Given a state map, return a mapping of Mesos Slave uuids to hostnames.

  Arguments:
      state: map

  Returns:
      {slave-uuid hostname}"
  [state]
  (let [slave->sid:host (fn [slave] {(get slave "id") (get slave "hostname")})]
    (reduce merge
            {}
            (map slave->sid:host (get state "slaves")))))

(defn state->tasks
  "Return the tasks in a Mesos state.

  Arguments:
      state: map

  Returns:
      tasks: collection"
  [state]
  (apply concat (for [f ["frameworks" "completed_frameworks"]
                      t ["tasks" "completed_tasks"]]
                  (relay/get-in-state state [f t]))))

(defn task->task-time
  "Return the time at which the task's most recent state change occurred.

  Arguments:
      task: map

  Returns:
      time of last state change: long"
  [task]
  (let [times (map #(get % "timestamp") (get task "statuses"))]
    (if (seq times)
      (apply max times)
      (System/currentTimeMillis))))

(defn state->recent-tasks
  "Given a state map, return tasks that have had a state change since a given
  time.

  Arguments:
      state: map
      time-last-checked: long, milliseconds since epoch

  Returns:
      tasks: collection"
  [state time-last-checked]
  (let [tasks (state->tasks state)]
    (filter #(> (task->task-time %)
                time-last-checked)
            tasks)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mesos Riemann events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mesos-leader-event
  "Track how many Mesos Master leaders this Satellite is tracking.

  It is more natural to track a boolean 'am I tracking the leader', but tracking
  a number 'number of leaders I am tracking' instead is advantageous. This means
  we can put the metric in the Riemann event's :metric field, which only takes a
  number and then leverage many of the metric-centric functions in Riemann.

  Arguments:
      mesos-master-url: cemerick.url.URL
      state: enum, string, 'ok', 'critical'
      master-type: enum, keyword, :leader, :follower, :unknown

  Returns:
      event tracking the number of leaders this Satellite is tracking."
  [mesos-master-url state master-type]
  (let [num-leaders (if (= :leader master-type) 1 0)]
    {:host (:host mesos-master-url)
     :service (str "mesos/leader")
     :state state
     :time (time/unix-now)
     :ttl 60
     :metric num-leaders}))

(defn mesos-service-events
  "Turn every value from /stats.json into a trackable event."
  [mesos-master-url]
  (let [metrics (:body (client/get (str mesos-master-url "/stats.json")
                                   {:as :json-string-keys}))
        transforms {"mem_percent" #(* 100 %)
                    "cpus_percent" #(* 100 %)}]
    (map
     (fn [[service metric]]
       {:host (:host mesos-master-url)
        :ttl 300
        :state "ok"
        :time (time/unix-now)
        :service (str "mesos/" service)
        :metric (if-let [transform (get transforms "transforms")]
                  (transform (double metric))
                  (double metric))})
     metrics)))

(defn mesos-framework-events
  [mesos-master-url]
  (flatten
   (map #(let [[hostname svc-name resources]
               (map % ["hostname" "name" "resources"])]
           [{:host hostname
             :state "ok"
             :ttl 300
             :time (time/unix-now)
             :service (str "mesos/frameworks/" svc-name "/mem")
             :metric (/ (float (get resources "mem")) 1000.0)}
            {:host hostname
             :state "ok"
             :time (time/unix-now)
             :ttl 300
             :service (str "mesos/frameworks/" svc-name "/cpu")
             :metric (get resources "cpus")}
            {:host hostname
             :state "ok"
             :time (time/unix-now)
             :ttl 300
             :service (str "mesos/frameworks/" svc-name "/disk")
             :metric (get resources "disk")}])
        (get-in (client/get (str mesos-master-url "/master/state.json")
                            {:as :json-string-keys})
                [:body "frameworks"]))))

(defn mesos-task-events
  "Track Mesos task states.

  Arguments:
      tasks: collection of tasks
      sid->host: map of slave uuid -> hostname

  Returns:
      collection of Riemann events"
  [tasks sid->host]
  (let [task->event (fn [task]
                      {:host (sid->host (get task "slave_id"))
                       :sid (get task "slave_id")
                       :state (get task "state")
                       :time (time/unix-now)
                       :ttl 300
                       :service "mesos/tasks"})]
    (map task->event tasks)))

(defn atom-events
  [atm]
  (let [kv->event (fn [[k v]]
                    {:host hostname
                     :service (str "mesos/" (name k))
                     :metric v
                     :ttl 300
                     :time (time/unix-now)})]
    (map kv->event @atm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main monitor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn do-monitor
  "The main monitor function.

  A loop that checks if the Satellite is tracking the Mesos Master leader, if so
  put a number of events into the Riemann stream for potential processing.

  Arguments:
      mesos-master-url: cemerick.url.URL, mesos to track
      sleep-time: long, time between loops
      core: Riemann core

  Returns:
      ~Never~"
  [{:keys [leader sleep-time core opts] :or {opts {}}}]
  (let [tcp (riemann.transport.tcp/tcp-server (assoc opts :core core))
        mesos-master-url (:mesos-master-url leader)]
    (riemann.service/start! tcp)
    (loop [time-last-checked (System/currentTimeMillis)]
      (log/info (str "Checking for tasks after " time-last-checked))
      (let [time-last-checked'
            (try
              (if (riemann.config/leader?)
                ;; Satellite leader
                (let [_ (log/info (str "Tracking leader " mesos-master-url))
                      state (get-state-map mesos-master-url)
                      recent-tasks (state->recent-tasks state time-last-checked)
                      sid->host (state-map->slave-map state)]
                  ;; leader event
                  (doseq [e (concat
                             [(mesos-leader-event mesos-master-url "ok" :leader)]
                             ;; services
                             (mesos-service-events mesos-master-url)
                             ;; frameworks
                             (mesos-framework-events mesos-master-url)
                             ;; tasks
                             (mesos-task-events recent-tasks sid->host)
                             ;; state
                             (atom-events satellite.services.stats/state))]
                    (riemann.core/stream! @core e))
                  (apply max (conj (map task->task-time recent-tasks)
                                   time-last-checked)))
                ;; not Satellite leader
                (do
                  (log/info (str "Tracking follower " mesos-master-url))
                  ((:stream!! (meta riemann.core/stream!))
                   @core
                   (mesos-leader-event mesos-master-url
                                       "ok"
                                       :follower))
                  (System/currentTimeMillis)))
              (catch Exception ex
                (log/error "Error in poll loop" ex)
                time-last-checked))]
        (try
          (Thread/sleep sleep-time)
          (catch Exception ex
            (log/error "Error in sleep" ex)))
        (recur time-last-checked')))))
