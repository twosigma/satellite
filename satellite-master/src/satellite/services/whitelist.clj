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

(ns satellite.services.whitelist
  (:require [clojure.tools.logging :as log]
            [satellite.whitelist :as whitelist]
            [liberator.core :refer (resource)]
            [satellite.services.stats :as stats]
            [clojure.data.json :as json]
            [schema.core :as s]
            [clojure.walk :as walk]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [swiss.arrows :refer (-<> -<>>)])
  (import java.util.concurrent.TimeUnit))

(defn str->period
  [str]
  (let [period->period-fn {"minute" t/minutes
                           "hour" t/hours
                           "day" t/days
                           "week" t/weeks}]
    (if-let [[_ num period] (re-find #"^(\d+)(minute|hour|day|week)s?$" str)]
      (let [period-fn (get period->period-fn period)]
        (period-fn (Integer/parseInt num)))
      (throw (RuntimeException. (format "Cannot parse ttl %s" str))))))

(defn add-time-and-ttl
  [event]
  (let [now (t/now)
        set-ttl-if-exists (fn [event]
                            (if-let [ttl (:ttl event)]
                              (-> event
                                  (assoc :ttl (-> ttl
                                                  (str->period)
                                                  (t/in-seconds))))
                              event))]
    (-> event
        (assoc :time (->> now
                          (tc/to-long)
                          (.toSeconds TimeUnit/MILLISECONDS)))
        (set-ttl-if-exists))))

(defn hostname-malformed?
  [whitelist-hostname-pred hostname]
  (try
    (let [addr (java.net.InetAddress/getByName hostname)]
      (cond
       (not (whitelist-hostname-pred hostname))
       [true {::msg "Hostname did not pass user supplied predicate."}]

       (.isReachable addr 1000)
       [true {::msg (format "%s is not reachable." hostname)}]))
    (catch java.net.UnknownHostException ex
      [true {::msg (format " %s is not DNS resolvable or an IPv4/6 address." hostname)}])))

(defn whitelist-host-service
  [curator cache zk-whitelist-path whitelist-hostname-pred host]
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:delete :get]
   :malformed? (fn [ctx]
                 (hostname-malformed? whitelist-hostname-pred host))
   :delete! (fn [ctx]
              (whitelist/delete-host curator zk-whitelist-path host)
              {::msg (str host " was removed from the whitelist.\n")})
   :handle-ok (fn [ctx]
                (:whitelist-data (whitelist/get-host cache zk-whitelist-path host)))
   :handle-malformed (fn [ctx]
                       (::msg ctx))))

(defn whitelist-host-event-service
  [curator cache zk-whitelist-path whitelist-hostname-pred host event-id]
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post :delete]
   :malformed? (fn [ctx]
                 (or (hostname-malformed? whitelist-hostname-pred host)
                     (when (#{:post} (get-in ctx [:request :request-method]))
                       (try
                         (let [data (-<> (slurp (get-in ctx [:request :body]))
                                         (json/read-str :key-fn keyword)
                                         (add-time-and-ttl)
                                         (s/validate whitelist/ManualEvent <>))]
                           [false {::data data}])
                         (catch Throwable e
                           [true {::msg (format "Exception: %s" (.getMessage e))}])))))
   :post! (fn [ctx]
           (let [event (get-in ctx [::data])]
             (whitelist/zk-update-manual-event-recompute-flag!
              curator cache zk-whitelist-path host event-id event)
             {::msg (str host " updated.\n")}))
   :delete! (fn [ctx]
              (whitelist/zk-update-manual-event-recompute-flag!
               curator cache zk-whitelist-path host event-id nil)
              {::msg (str event-id " was removed from the whitelist.\n")})
   :handle-ok (fn [ctx]
                (:whitelist-data (whitelist/get-host cache zk-whitelist-path host)))
   :handle-not-found (fn [ctx]
                       (::msg ctx))
   :handle-malformed (fn [ctx]
                       (::msg ctx))
   :handle-created (fn [ctx]
                     (::msg ctx))))

(defn whitelist-list-service
  [whitelist-cache flag]
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]
   :malformed? (fn [ctx]
                 (when-not (#{"on" "off" "all"} flag)
                   [true {::msg "Unsupported flag/filter."}]))
   :handle-ok (fn [ctx]
                (let [hosts (condp = flag
                              "on" (whitelist/get-on-hosts whitelist-cache)
                              "off" (whitelist/get-off-hosts whitelist-cache)
                              "all" (whitelist/get-all-hosts whitelist-cache))]
                  hosts))
   :handle-malformed (fn [ctx]
                       (::msg ctx))))
