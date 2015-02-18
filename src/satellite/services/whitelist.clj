(ns satellite.services.whitelist
  (:require [satellite.whitelist :as whitelist]
            [liberator.core :refer (resource)]
            [satellite.services.stats :as stats]
            [satellite.time :as time]))

(defn whitelist-host-flag-service
  [cache curator zk-whitelist-path whitelist-hostname-pred host flag]
  (resource
   :available-media-types ["text/html" "application/json"]
   :allowed-methods [:put]
   :malformed? (fn [ctx]
                 (condp = flag
                   "on" (try
                          (let [addr (java.net.InetAddress/getByName host)
                                thirty-seconds (-> 30 time/seconds)]
                            (cond
                             (not (.isReachable addr thirty-seconds))
                             [true {::error "Host is unreachable."}]
                             (not (whitelist-hostname-pred host))
                             [true {::error "Hostname did not pass user supplied predicate."}]))
                          (catch java.net.UnknownHostException ex
                            [true {::error "Host is not DNS resolvable or an IPv4/6 address."}]))
                   "off" false
                   :else [true {::error "Unsupported flag."}]))
   :put! (fn [ctx]
           (if (= flag "on")
             (do
               (whitelist/on-host cache curator zk-whitelist-path host)
               {::msg (str host "is now on.\n")})
             (do
               (whitelist/off-host cache curator zk-whitelist-path host)
               {::msg (str host "is now off.\n")})))
   :handle-malformed (fn [ctx]
                       (::error ctx))
   :handle-created (fn [ctx]
                     (::msg ctx))))

(defn whitelist-host-st-rm-service
  [cache curator zk-whitelist-path host]
  (resource
   :available-media-types ["text/html" "application/json"]
   :allowed-methods [:get :delete]
   :exists? (fn [ctx]
              (when-let [flag (whitelist/get-host cache host)]
                (cond
                 (not (= :get (get-in ctx [:request :request-method]))) true
                 (= flag :on) [true {::msg "On\n"}]
                 (= flag :off) [false {::msg "Off\n"}]
                 :else (throw (Exception.
                               (str "Get request retrieved status " flag))))))
   :delete! (fn [ctx]
              (whitelist/rm-host curator zk-whitelist-path host)
              {::msg (str host " is now removed from the cluster.")})

   :handle-ok (fn [ctx]
                (::msg ctx))
   :handle-no-content (fn [ctx]
                        (::msg ctx))
   :handle-not-found (fn [ctx]
                       (::msg ctx))))

(defn whitelist-list-service
  [cache flag]
  (resource
   :available-media-types ["text/html" "application/json"]
   :allowed-methods [:get]
   :malformed? (fn [ctx]
                 (when-not (#{"on" "off" "all"} flag)
                   [true {::msg "Unsupported flag/filter."}]))
   :handle-ok (fn [ctx]
                (let [hosts (condp = flag
                              "on" (whitelist/get-on-hosts cache)
                              "off" (whitelist/get-off-hosts cache)
                              "all" (whitelist/get-all-hosts cache))]
                  (clojure.string/join "\n" (sort hosts))))
   :handle-malformed (fn [ctx]
                       (::msg ctx))))
