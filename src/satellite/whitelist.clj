(ns satellite.whitelist
  (require [clojure.tools.logging :as log]
           [chime]
           [clojure.core.async :as async]
           [clj-time.core :as t]
           [clj-time.periodic :as periodic]
           [satellite.services.stats :as stats]
           [riemann.core]
           [satellite.time :as time])
  (import org.apache.curator.framework.CuratorFrameworkFactory
          org.apache.curator.framework.CuratorFramework
          org.apache.curator.retry.BoundedExponentialBackoffRetry
          org.apache.curator.framework.recipes.cache.PathChildrenCache
          org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent
          org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent$Type
          org.apache.curator.framework.recipes.cache.PathChildrenCacheListener
          org.apache.zookeeper.KeeperException
          org.apache.zookeeper.KeeperException$Code))

;; todo, move to Curator 2.7.0, use TreeCache, use CAS of hosts
;; this would allow scale for 10000x more hosts
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
                         (log/info "Host added")
                         (async/go
                          (async/>! sync-ch :update)))
                       PathChildrenCacheEvent$Type/CHILD_REMOVED
                       (do
                         (log/info "Host removed")
                         (async/go
                          (async/>! sync-ch :update)))
                       PathChildrenCacheEvent$Type/CHILD_UPDATED
                       (do
                         (log/info "Host updated")
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

(defn childData->enum
  "Read the data contained in the childData.

  Arguments:
      c, childData

  Returns:
      clojure keyword"
  [c]
  (when c
    (read-string (String. (.. c getData)))))

(defn childData-on?
  "Predicate, does the childData say the host should be on?"
  [c]
  (#{:on} (childData->enum c)))

(defn childData-off?
  "Predidate, does the childData say the host should be off?"
  [c]
  (#{:off} (childData->enum c)))

(defn zkPath->host
  "Take the fullpath and return the hostname"
  [path]
  (last (clojure.string/split path #"/")))

(defn write-out-cache!
  "Write out the cached whitelist to disk in sorted order.

  Arguments:
      wtr: writer
      cache: PathChildrenCache

  Returns:
      nil"
  [wtr cache]
  (log/info "Writing out host cache")
  (let [all-hosts (.. cache getCurrentData)
        num-available-hosts (count all-hosts)
        on-hosts (filter childData-on? all-hosts)
        num-on-hosts (count on-hosts)
        hostnames (map #(-> (.. % getPath) zkPath->host) on-hosts)]
    (log/info (str "Whitelist will contain " num-on-hosts
                   " of " num-available-hosts " hosts."))
    (reset! stats/num-available-hosts num-available-hosts)
    (reset! stats/num-hosts-up num-on-hosts)
    (doseq [host (sort hostnames)]
      (log/debug (str "On: " host))
      (.write wtr host)
      (.write wtr "\n"))))

(defn batch-sync
  "Create a curator cache and schedule recurring syncs of the whitelist.

  Every sync-period seconds, check to see if a request to sync has been made,
  and if so, call the sync-fn. The request to sync is made by the Curator
  PathChildrenCache. The cache and the syncer are returned so the caller may
  close or stop either.

  Arguments:
      curator: a Curator client
      zk-whitelist-path: string, path on Zookeeper in which children correspond
                                 to host status
      local-whitelist-path: string, path to local disk, the Mesos whitelist
      sync-period: long, milliseconds
      sync-fn: fn (cache : PathChildrenCache) : Any

  Returns:
      {:cache, PathChildrenCache
       :sync,  return of chime, a 0-arity function that, when called, stops the
               schedule}"
  [curator zk-whitelist-path sync-period sync-fn]
  (let [sync-ch (async/chan (async/sliding-buffer 1))
        cache   (path-cache curator zk-whitelist-path sync-ch)
        _       (.start cache)
        sync    (chime/chime-at (periodic/periodic-seq (t/now) (t/millis sync-period))
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
                                {:error-handler (fn [ex] (log/error ex))})]
    {:cache cache
     :sync  sync}))

(defn stream-host-counts
  [core cache sync-period]
  (chime/chime-at (periodic/periodic-seq (t/now) (t/millis sync-period))
                  (fn [_]
                    (let [all-hosts (.. cache getCurrentData)
                          num-available-hosts (count all-hosts)
                          on-hosts (filter childData-on? all-hosts)
                          num-on-hosts (count on-hosts)]
                      (riemann.core/stream! @core
                                            {:service "num-inventory-hosts"
                                             :metric num-available-hosts})
                      (riemann.core/stream! @core
                                            {:service "num-on-hosts"
                                             :metric num-on-hosts})))))

(defn initialize-whitelist
  "Copy current whitelist to zookeeper.

  Caller should be leader, and it is assumed the zk-whitelist-path does not
  exist on the zookeeper.

  Arguments:
      rdr: reader or nil
      curator: Curator client
      zk-whitelist-path: path on zookeeper with host key-values

  Returns:
      nil"
  [rdr curator zk-whitelist-path]
  (when (.. curator checkExists (forPath zk-whitelist-path))
    (throw (ex-info "Whitelist node should not exist."
                    {:zk-whitelist-path zk-whitelist-path})))
  (log/info "Initializing whitelist")
  (.. curator create (forPath zk-whitelist-path (byte-array 0)))
  (when rdr
    (with-open [rdr rdr]
      (doseq [host (line-seq rdr)]
        (.. curator create (forPath (str zk-whitelist-path "/" host)
                                    (.getBytes (str :on))))))))

(defn update-host
  "Update host status on zookeeper.

  Arguments:
      flag: keyword, the status
      curator: Curator client
      zk-whitelist-path: path on zookeeper with host key-values
      host: host to update

  Returns:
      nil"
  [flag curator zk-whitelist-path host]
  (let [flag (.getBytes (str flag))
        full-path (str zk-whitelist-path
                       (when-not (.endsWith zk-whitelist-path "/")
                         "/")
                       host)]
    (try
      (.. curator create (forPath full-path flag))
      (catch org.apache.zookeeper.KeeperException ex
        (if (= (.code ex) org.apache.zookeeper.KeeperException$Code/NODEEXISTS)
          (.. curator setData (forPath full-path flag))
          (throw ex))))))

(defn guarded-update-host
  [flag pred cache curator zk-whitelist-path host]
  (let [state (.. cache (getCurrentData (str zk-whitelist-path "/" host)))]
    (when-not (pred state)
      (let [named-state (when-not (nil? state) (name (childData->enum state)))]
        (log/info "Turning" host named-state "->" (name flag))
        (update-host flag curator zk-whitelist-path host)))))

(defn on-host
  "Turn on host. WARNING: Only proceeds when cache says node is not already on."
  [cache curator zk-whitelist-path host]
  (guarded-update-host :on childData-on?
                       cache curator zk-whitelist-path host))

(defn off-host
  "Turn off host. WARNING: Only proceeds when cache says node is not already off."
  [cache curator zk-whitelist-path host]
  (guarded-update-host :off childData-off?
                       cache curator zk-whitelist-path host))

(defn delete-host
  "Update host status on zookeeper.

  Arguments:
      curator: Curator client
      zk-whitelist-path: path on zookeeper with host key-values
      host: host to update

  Returns:
      bool, true if successful, else false"
  [curator zk-whitelist-path host]
  (let [full-path (str zk-whitelist-path
                       (when-not (.endsWith zk-whitelist-path "/")
                         "/")
                       host)]
    (when (.. curator checkExists (forPath full-path))
      (.. curator delete (forPath full-path))
      true)))

(def rm-host delete-host)

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
    (set (map #(-> (.. % getPath) zkPath->host) filtered-hosts))))

(def get-all-hosts
  (partial get-hosts identity))

(def get-on-hosts
  (partial get-hosts childData-on?))

(def get-off-hosts
  (partial get-hosts childData-off?))

(defn get-host
  "Get status of host from cache.

  Todo: make one, not two lookups

  Arguments:
      cache: PathChildrenCache
      host: hostname, String

  Returns:
      :on, :off, or nil; nil when host is not in cluster inventory"
  [cache host]
  (cond
   (not ((get-all-hosts cache) host)) nil
   ((get-on-hosts cache) host) :on
   :else :off))

(defn write-to-zk!
  [curator path bytes]
  (try
    (.. curator create (forPath path bytes))
    (catch org.apache.zookeeper.KeeperException ex
      (if (= (.code ex) org.apache.zookeeper.KeeperException$Code/NODEEXISTS)
        (.. curator setData (forPath path bytes))
        (throw ex)))))

(defn merge-cd-into-whitelist!
  "Take a childData and if different than what is in whitelist-cache, merge.

  When we consider two childData, only apply the childData whose path is first.
  Following merge semantics, cd-b is preferred to cd-a in case of equality.

  Return the result of (compare host-a host-b) so caller knows who was synced."
  ([curator zk-whitelist-path whitelist-cache cd]
   (let [host (zkPath->host (.getPath cd))
         path (str zk-whitelist-path "/" host)
         whitelist-cd (.. whitelist-cache (getCurrentData path))]
     ;; when not in inventory, or data is different
     (when (or (nil? whitelist-cd)
               (not (= (seq (.. whitelist-cd getData))
                       (seq (.. cd getData)))))
       (write-to-zk! curator path (.. cd getData)))
     0))
  ([curator zk-whitelist-path whitelist-cache cd-a cd-b]
   (let [host-a (zkPath->host (.getPath cd-a))
         host-b (zkPath->host (.getPath cd-b))
         cmp (compare host-a host-b)]
     ;; only do cd-a if comes before cd-b
     (if (neg? cmp)
       (merge-cd-into-whitelist! curator zk-whitelist-path whitelist-cache cd-a)
       (merge-cd-into-whitelist! curator zk-whitelist-path whitelist-cache cd-b))
     cmp)))

(defn merge-whitelist-caches!
  "Sync whitelist-cache with the managed and manual-caches. Choose manual cache
  in case of conflict

  Arguments:
      curator: Curator client
      zk-whitelist-path: String, prefix of whitelist-cache
      whitelist-cache: PathChildrenCache, the cache of what Mesos interacts
                       with, not public facing
      managed-cache: PathChildrenCache, the automatically managed cache, public
                     facing
      manual-cache: PathChildrenCache, the manually managed cache, public facing

  Returns
      :done, when completed"
  [curator zk-whitelist-path whitelist-cache managed-cache manual-cache]
  (loop [[manual-cd  & manual-cds
          :as manual-cdss]  (.getCurrentData manual-cache)
          [managed-cd & managed-cds
           :as managed-cdss] (.getCurrentData managed-cache)]
    (cond
      (and (nil? manual-cd)
           (nil? managed-cd))
      :done
      (nil? manual-cd)
      (recur managed-cdss
             nil)
      (nil? managed-cd)
      (do (merge-cd-into-whitelist! curator zk-whitelist-path whitelist-cache
                                    manual-cd)
          (recur manual-cds
                 nil))
      :else
      (let [cmp (merge-cd-into-whitelist! curator zk-whitelist-path whitelist-cache
                                          managed-cd manual-cd)]
        (cond
          ;; peel managed
          (neg? cmp) (recur manual-cdss
                            managed-cds)
          ;; peel manual
          (pos? cmp) (recur manual-cds
                            managed-cdss)
          ;; peel both
          :else (recur manual-cds
                       managed-cds))))))
