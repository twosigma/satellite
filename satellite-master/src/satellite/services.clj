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

(ns satellite.services
  (:require [compojure.core :refer (ANY)]
            [satellite.services.state :as state]
            [satellite.services.stats :as stats]
            [satellite.services.whitelist :as whitelist]))

(defn service
  [{:keys [bucket whitelist-cache manual-cache curator riak-conn
           whitelist-hostname-pred
           zk-whitelist-path]}]
  (-> (compojure.core/routes
       ;; task metadata endpoint; requires riak
       (ANY "/state.json" []
         (state/tasks-metadata riak-conn bucket))
       (ANY "/stats.json" []
         (stats/stats))
       (ANY "/whitelist/host/:host" [host]
         (whitelist/whitelist-host-st-rm-service
          whitelist-cache
          curator
          zk-whitelist-path
          host))
       (ANY "/whitelist/host/:host/:flag" [host flag]
         (whitelist/whitelist-host-flag-service
          manual-cache
          curator
          zk-whitelist-path
          whitelist-hostname-pred
          host
          flag))
       (ANY "/whitelist/:flag" [flag]
         (whitelist/whitelist-list-service
          whitelist-cache
          flag))
       (ANY "*" req
         (ring.util.response/not-found "Not a supported endpoint.")))))
