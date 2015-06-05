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

(ns satellite-slave.mesos.cache
  (:require [clj-http.client :as http]
            [clj-time.periodic :refer [periodic-seq]]
            [clojure.pprint :refer [pprint]]
            [clj-time.core :as t]
            [clojure.tools.logging :as log]
            [clojure.core.memoize :as memo]
            [clojure.core.cache :as cache]
            [satellite-slave.relay :as relay]
            [satellite-slave.util :refer [every]]
            [cheshire.core :as json]))

(defn get-task-ids
  "Return a list of all task ids tracked in a state.

   Args:
       state: Map, specifies the state where the task ids could be retrieved from.
   Output:
       A list of all task ids."
  [state]
  (let [;; Take the state as a tree and retrieve task id from the leaves via
        ;; a depth-first walk.
        walker (fn walker [[k & ks] node]
                 (if (and (seq ks)
                          (seq (get node k)))
                   (mapcat (partial walker ks) (get node k))
                   (when (nil? ks)
                     [(get node k)])))
        combs (for [framework-category ["frameworks" "completed_frameworks"]
                    executor-category ["executors" "completed_executors"]
                    task-category ["tasks" "completed_tasks"]]
                [framework-category executor-category task-category "id"])]
    (mapcat #(walker % state) combs)))

(defn post-state*
  "Given a task-id and a state, trim the state to include the task related
   information only and post it to riak. The riak key is the task-id and the
   value is the trimmed state as json.

   Args:
       state: Map, specifies the state map.
       riak-url: String, specifies the riak uri.
       bucket: String, specifies the riak bucket to hold the riak key value.
       task-id: String, specifies the task id.
   Throw:
       SocketTimeoutException when socket connection timeout.
       ConnectionTimeoutException when http connection timeout.
       ExceptionInfo when the status code is >= 300 when sending http post request.
   Output:
       A key :success if the given state is posted successfully otherwise
       simply nil."
  [state riak-url bucket task-id]
  (let [minimal-state (relay/state->minimal-task-state #{task-id} state)
        resp (http/post (format "%s/%s/%s" riak-url bucket task-id)
                        {:body (json/generate-string minimal-state)
                         :socket-timeout (* 5 1000) ;; in milliseconds
                         :conn-timeout (* 5 1000)   ;; in milliseconds
                         :content-type :json})]
    (if (< (:status resp) 300)
      (do
        (log/info "Successfully post state for task" task-id)
        :success)
      (throw (ex-info "Failed to post state." {:error resp})))))

;; Should not use ttl cache as this fucntion could be invoked periodically.
(let [cache (atom (cache/lru-cache-factory {} :threshold 16384))]
  (defn post-state
    "Given a task-id and a state, trim the state to include the task related
     information only and post it to riak. The riak key is the task-id and the
     value is the trimmed state as json. Task states will only be posted if
     they have not already been posted recently.

     Args:
         state: Map, specifies the state map.
         riak-url: String, specifies the riak uri.
         bucket: String, specifies the riak bucket to hold the riak key value.
         task-id: String, specifies the task id.
     Output:
         A key :success if the given state is posted successfully or was successfully
         posted recently. Otherwise it simply returns nil."
    [state riak-url bucket task-id]
    (let [post (delay (try
                        ;; It returns :success if it posts successfully and nil otherwise.
                        (post-state* state riak-url bucket task-id)
                        (catch Exception e
                          (log/warn e "Failed to post state for task" task-id "purging from cache...")
                          (swap! cache cache/evict task-id)
                          nil)))
          cs (swap! cache (fn [c]
                            (if (cache/has? c task-id)
                              (cache/hit c task-id)
                              (cache/miss c task-id post))))
          ;; val could be nil (very unlikely) as a key could be evicted
          ;; naturally by the cache size limitation after the swap!.
          val (cache/lookup cs task-id)]
      ;; Try to actually post state for a task here. If it fails, it will
      ;; evict the cache.
      (if val @val @post))))

(defn cache-state-and-count-failures
  "Persist the state of mesos slave to riak.

   Args:
       slave-host: String, specifies the mesos slave where the state could be retrieved from.
       riak-url: String, specifies the riak uri.
       bucket: String, specifies the riak bucket to hold the riak key value.
   Output:
       The number of tasks which are failed to persist their states.
   Throw:
       ExceptionInfo when can not retrieve state from the slave host."
  [slave-host riak-url bucket]
  (let [{:keys [body error]} (relay/get-state slave-host)]
    (if error
      (throw (ex-info (str "Failed to get state.json from host" slave-host) {:error error}))
      (let [state (json/parse-string body)]
        (->> (http/with-connection-pool {:threads 4}
               ;; Force all posts.
               (doall (pmap (partial post-state state riak-url bucket) (get-task-ids state))))
             ;; Filter out those success posts and count the failed.
             (filter nil?)
             count)))))
