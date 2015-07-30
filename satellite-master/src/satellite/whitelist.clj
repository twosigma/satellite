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

(ns satellite.whitelist
  (require [clojure.tools.logging :as log]
           [chime]
           [clojure.core.async :as async]
           [clj-time.core :as t]
           [clj-time.periodic :as periodic]
           [satellite.services.stats :as stats]
           [riemann.config]
           [riemann.core]
           [schema.core :as s]
           [schema.coerce :as coerce]
           [clojure.data.json :as json]
           [clojure.walk :as walk]
           [clj-time.core :as t]
           [clj-time.coerce :as tc])
  (import org.apache.curator.framework.CuratorFrameworkFactory
          org.apache.curator.framework.CuratorFramework
          org.apache.curator.retry.BoundedExponentialBackoffRetry
          org.apache.curator.framework.recipes.cache.PathChildrenCache
          org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent
          org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent$Type
          org.apache.curator.framework.recipes.cache.PathChildrenCacheListener
          org.apache.zookeeper.KeeperException
          org.apache.zookeeper.KeeperException$Code
          java.util.concurrent.TimeUnit))

(def RiemannEvent
  {(s/optional-key :host) s/Str
   (s/optional-key :service) s/Str
   :state (s/enum "ok" "critical" "warn" "expired")
   (s/optional-key :time) (s/maybe s/Num)
   (s/optional-key :description) (s/maybe s/Str)
   (s/optional-key :tags) (s/maybe [s/Str])
   (s/optional-key :metric) (s/maybe s/Num)
   (s/optional-key :ttl) (s/maybe s/Num)
   s/Keyword (s/maybe s/Str)})

(def ManualEvent
  RiemannEvent)

(def ManagedEvent
  RiemannEvent)

(def ValidStr
  (letfn [(not-too-long? [str] (<= (count str) 1024))]
    (s/both s/Str (s/pred not-too-long? 'not-too-long?))))

(def WhitelistData
  {:managed-events {ValidStr ManagedEvent}
   :manual-events {ValidStr ManualEvent}
   :managed-flag (s/enum "on" "off")
   :manual-flag (s/enum "on" "off")})

(defn new-whitelist-data
  []
  {:managed-events {}
   :manual-events {}
   :managed-flag nil
   :manual-flag nil})

(defn ->full-path
  [zk-path host]
  (str zk-path
       (when-not (.endsWith zk-path "/")
         "/")
       host))

(defn full-path->host
  [full-path]
  (last (clojure.string/split full-path #"/")))

(defn whitelist-data->bytes
  [whitelist-data]
  (-> whitelist-data
      (json/write-str)
      (.getBytes "UTF-8")))

(defn bytes->whitelist-data
    [bytes]
    (-> bytes
        (String. "UTF-8")
        (json/read-str :key-fn keyword
                       :value-fn (fn [k v]
                                   (if (#{:manual-events :managed-events} k)
                                     (->> v
                                          (map (fn [[k1 v1]] [(subs (str k1) 1) v1]))
                                          (into {}))
                                     v)))))

(defn path-cache
  "Create a PathChildrenCache with an attached childEvent listener.

  Arguments:
      curator: Curator client
      zk-whitelist-path: listen to this path
      sync-ch: send signals to this async channel

  Returns:
      cache, PathChildrenCache"
  [curator zk-whitelist-path sync-ch]
  (let [cache   (PathChildrenCache. curator zk-whitelist-path true)
        listener (reify PathChildrenCacheListener
                   (childEvent [this client event]
                     (condp = (.getType event)
                       PathChildrenCacheEvent$Type/CHILD_ADDED
                       (do
                         (async/go
                           (async/>! sync-ch :update)))
                       PathChildrenCacheEvent$Type/CHILD_REMOVED
                       (do
                         (async/go
                           (async/>! sync-ch :update)))
                       PathChildrenCacheEvent$Type/CHILD_UPDATED
                       (do
                         (async/go
                           (async/>! sync-ch :update)))
                       PathChildrenCacheEvent$Type/CONNECTION_LOST
                       (log/error "Lost connection to zk whitelist")
                       PathChildrenCacheEvent$Type/CONNECTION_RECONNECTED
                       (do
                         (log/info "Reconnected to zk whitelist")
                         (async/go
                           (async/>! sync-ch :update)))
                       PathChildrenCacheEvent$Type/CONNECTION_SUSPENDED
                       (log/error "Connection suspended")
                       PathChildrenCacheEvent$Type/INITIALIZED
                       (do
                         (log/info "Initalized watching of whitelist")
                         (async/go
                           (async/>! sync-ch :update)))
                       (log/error "Impossible event"))
                     nil))]
    (.. cache
        getListenable
        (addListener listener))
    cache))

(defn zk-delete!
  "Delete host in zookeeper.

  Arguments:
      curator: Curator client
      zk-whitelist-path: path on zookeeper with host key-values
      host: host to delete

  Returns:
      true if data is changed
      false if data is unchanged"
  [curator path]
  (if (.. curator checkExists (forPath path))
    (do (.. curator delete (forPath path))
        true)
    false))

(defn zk-set-or-create!
  [curator path version bytes]
  (if version
    (.. curator setData (withVersion version) (forPath path bytes))
    (.. curator create (forPath path bytes))))

(defn whitelist-data-on?
  [whitelist-data]
  (if-let [manual-flag (:manual-flag whitelist-data)]
    (= "on" manual-flag)
    (= "on" (:managed-flag whitelist-data))))

(def whitelist-data-off?
  (complement whitelist-data-on?))

(defn child-data-on?
  [child-data]
  (-> child-data
      (.getData)
      (bytes->whitelist-data)
      (whitelist-data-on?)))

(def child-data-off?
  (complement child-data-on?))

(defn get-host
  ([cache zk-whitelist-path host]
     (get-host cache zk-whitelist-path host nil))
  ([cache zk-whitelist-path host default-value]
     (let [full-path (->full-path zk-whitelist-path host)
           child-data (.getCurrentData cache full-path)]
       {:version (some-> child-data
                         (.getStat)
                         (.getVersion))
        :whitelist-data (or (some-> child-data
                                    (.getData)
                                    (bytes->whitelist-data))
                            default-value)})))

(defn delete-host
  [curator zk-whitelist-path host]
  (zk-delete! curator (->full-path zk-whitelist-path host)))

(defn recompute-manual-flag
  [whitelist-data]
  (let [new-manual-flag (some->> whitelist-data
                                 :manual-events
                                 (map (fn [[_ event]] [(:time event) (:state event)]))
                                 (sort-by first)
                                 (last)
                                 (second)
                                 ({"ok" "on" "critical" "off"}))]
    (assoc whitelist-data :manual-flag new-manual-flag)))

(defn set-flag
  [whitelist-data type flag]
  (let [key (case type
              :manual :manual-flag
              :managed :managed-flag)
        wd (if whitelist-data
             whitelist-data
             (new-whitelist-data))]
    (assoc wd key flag)))

(defn set-event
  [whitelist-data type key event]
  (let [wd (if whitelist-data
             whitelist-data
             (new-whitelist-data))
        type-key (case type
              :manual :manual-events
              :managed :managed-events)]
    (if event
      (update-in wd [type-key] assoc key event)
      (update-in wd [type-key] dissoc key))))

(defn zk-swap!
  "Swap operation on a zookeeper node."
  [curator cache zk-whitelist-path host f]
  (loop [n 0]
    (when (>= n 4)
      (throw (RuntimeException. "Failed to update host. Max conflict count exceeded.")))
    (let [result (try
                   (let [{:keys [version whitelist-data]} (get-host cache zk-whitelist-path host)
                         whitelist-data' (f whitelist-data)]
                     (zk-set-or-create! curator
                                        (->full-path zk-whitelist-path host)
                                        version
                                        (whitelist-data->bytes whitelist-data'))
                     whitelist-data')
                   (catch KeeperException ex
                     ;; We perform a read-modify-write here. If the value read is stale, we will get one
                     ;; of the following exception, depending on whether or not the node exists in the cache
                     (if (#{org.apache.zookeeper.KeeperException$Code/BADVERSION
                            org.apache.zookeeper.KeeperException$Code/NODEEXISTS
                            org.apache.zookeeper.KeeperException$Code/NONODE}
                          (.code ex))
                       (do
                         (.rebuildNode cache (->full-path zk-whitelist-path host))
                         :retry)
                       (throw ex))))]
      (if (= :retry result)
        (recur (inc n))
        result))))

(defn zk-update-manual-event-recompute-flag!
  [curator cache zk-whitelist-path host key event]
  {:pre [(not (nil? key))
         (or (nil? event)
             (s/validate ManualEvent event))]}
  (letfn [(f [whitelist-data]
            (let [wd (if whitelist-data
                       whitelist-data
                       (new-whitelist-data))]
              (-> wd
                  (set-event :manual key event)
                  (recompute-manual-flag))))]
    (zk-swap! curator cache zk-whitelist-path host f)))

(defn zk-update-flag!
  [curator cache zk-whitelist-path host type flag]
  {:pre [(#{:manual :managed} type)
         (#{"on" "off"} flag)]}
  (zk-swap! curator cache zk-whitelist-path host (fn [wd] (set-flag wd type flag))))

(defn zk-update-event!
  "Update an event for a host in zookeeper. If the event is nil, remove the event.

  This function will always create the host node if it doesn't exist"
  [curator cache zk-whitelist-path host type key event]
  {:pre [(not (nil? key))
         (#{:manual :managed} type)
         (or (nil? event)
             (case type
               :manual (s/validate ManualEvent event)
               :managed (s/validate ManagedEvent event)))]}
  (zk-swap! curator cache zk-whitelist-path host (fn [wd] (set-event wd type key event))))

(defn get-hosts
  "Get hosts from cache.

  Arguments:
      pred: predicate function, 1-arity, operates on childData
      cache: PathChildrenCache

  Returns:
      set of hostnames passing pred"
  [pred cache]
  (let [all-hosts (.. cache getCurrentData)
        filtered-hosts (filter pred all-hosts)]
    (->> all-hosts
         (map (fn [cache]
                (let [full-path (.. cache getPath)]
                  [(-> cache
                       (.. getPath)
                       (full-path->host))
                   (-> cache
                       (.getData)
                       (bytes->whitelist-data))])))
         (filter #(pred (second %)))
         (into {}))))

(def get-all-hosts
  (partial get-hosts (constantly true)))

(def get-on-hosts
  (partial get-hosts whitelist-data-on?))

(def get-off-hosts
  (partial get-hosts whitelist-data-off?))

(defn on-host
  [curator cache zk-whitelist-path host]
  (zk-update-flag! curator cache zk-whitelist-path host :managed "on"))

(defn off-host
  [curator cache zk-whitelist-path host]
  (zk-update-flag! curator cache zk-whitelist-path host :managed "off"))

(defn persist-event
  [curator cache zk-whitelist-path host key event]
  (zk-update-event! curator cache zk-whitelist-path host :managed key event))

(defn delete-event
  [curator cache zk-whitelist-path host key]
  (zk-update-event! curator cache zk-whitelist-path host :managed key nil))

(defn write-out-cache!
  "Write out the cached whitelist to disk in sorted order.

  Arguments:
      wtr: writer
      cache: PathChildrenCache

  Returns:
      nil"
  [wtr cache]
  (let [all-child-datas (.. cache getCurrentData)
        num-all-child-datas (count all-child-datas)
        on-child-datas (filter child-data-on? all-child-datas)
        num-on-child-datas (count on-child-datas)
        hostnames (map #(-> (.. % getPath) full-path->host) on-child-datas)]
    (log/info (str "Whitelist will contain " num-on-child-datas
                   " of " num-all-child-datas " hosts."))
    (reset! stats/num-available-hosts num-all-child-datas)
    (reset! stats/num-hosts-up num-on-child-datas)
    (doseq [host (sort hostnames)]
      (log/debug (str "On: " host))
      (.write wtr host)
      (.write wtr "\n"))))

(defn start-cache!
"Create a curator cache and start it

  Arguments:
      curator: a Curator client
      zk-whitelist-path: string, path on Zookeeper in which children correspond
                                 to host status
      sync-ch async channel for notification
  Returns:
      PathChildrenCache"
  [curator zk-whitelist-path sync-ch]
  (let [cache (path-cache curator zk-whitelist-path sync-ch)
        _ (.start cache)]
    cache))

(defn start-batch-sync!
"Create a schedule recurring syncs of the whitelist.

  Every sync-period seconds, check to see if a request to sync has been made,
  and if so, call the sync-fn. The request to sync is made by the Curator
  PathChildrenCache.

  Arguments:
      curator: a Curator client
      cache: path cache
      zk-whitelist-path: string, path on Zookeeper in which children correspond
                                 to host status
      sync-ch: async channel for notification
      sync-period: sync period
      sync-fn: fn (cache : PathChildrenCache) : Any

  Returns:
       return of chime, a 0-arity function that, when called, stops the schedule"
  [curator cache zk-whitelist-path sync-ch sync-period sync-fn]
  (chime/chime-at (periodic/periodic-seq (t/now) sync-period)
                  (fn [_]
                    (async/go
                     ;; return value here is only for more self-
                     ;; documenting code; the keyword is not used
                     ;; anywhere
                     (async/alt!
                      sync-ch ([_]
                                 (sync-fn cache)
                                 :synced)
                      :default   :did-not-sync
                      :priority  true)))
                  {:error-handler (fn [ex] (log/error ex))}))

(defn start-reaper!
  [curator cache zk-whitelist-path]
  (letfn [(event-expired? [now event]
            (if-let [ttl (:ttl event)]
              (t/after? now
                        (->>  (:time event)
                              (+ ttl)
                              (.toMillis TimeUnit/SECONDS)
                              (tc/from-long)))))]
    (chime/chime-at (periodic/periodic-seq (t/now) (t/minutes 1))
                    (fn [now]
                      (let [all-child-datas (.getCurrentData cache)]
                        (doseq [child-data all-child-datas]
                          (let [hostname (-> child-data
                                             (.getPath)
                                             (full-path->host))
                                expired-event-ids (->> child-data
                                                    (.getData)
                                                    (bytes->whitelist-data)
                                                    :manual-events
                                                    (filter (fn [[k v]]
                                                              (event-expired? now v)))
                                                    (map first))]
                            (doseq [id expired-event-ids]
                              (log/info "Expiring event for host " hostname ":" id)
                              (zk-update-manual-event-recompute-flag! curator
                                                                      cache
                                                                      zk-whitelist-path
                                                                      hostname
                                                                      id
                                                                      nil))))))
                    {:error-handler (fn [ex] (log/error ex))})))

(comment
  (def client-atom (atom nil))
  (let [zookeeper "rers1.pit.twosigma.com:2181,rers2.pit.twosigma.com:2181,rers3.pit.twosigma.com:2181"
        session-timeout (-> 3 time/minutes)
        connection-timeout (-> 30 time/seconds)
        curator-retry-policy (org.apache.curator.retry.BoundedExponentialBackoffRetry.
                              1000
                              10
                              3000)
        client (CuratorFrameworkFactory/newClient
                zookeeper session-timeout connection-timeout
                curator-retry-policy)]
    (reset! client-atom client)
    )
  (. @client-atom start)
  (def curator @client-atom)
  (def sync-ch (async/chan (async/sliding-buffer 1)))
  (def cache (path-cache curator "/whitelist-ljin" sync-ch))
  (def manual-cache (path-cache curator "/whitelist-manual-ljin" sync-ch))
  (.. curator checkExists (forPath "/whitelist-ljin"))

  (.. curator setData (withVersion 4) (forPath "/whitelist-ljin/dummy-host" (byte-array 0)))
  (.. cache start)
  (.. manual-cache start)

  (.getCurrentData cache "/whitelist-ljin/dummy-host")

  (-> cache (.getCurrentData "/whitelist-ljin/dummy-host") (.getStat) (.getVersion))

  (get-host curator cache "/whitelist-ljin" "dummyhost2")

  (delete-host curator cache "/whitelist-ljin" "dummyhost2")

  (get-off-hosts cache))
