(ns satellite.services
  (:require [compojure.core :refer (ANY)]
            [satellite.services.state :as state]
            [satellite.services.stats :as stats]
            [satellite.services.whitelist :as whitelist]))

(defn service
  [{:keys [bucket cache curator riak-conn
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
             cache
             curator
             zk-whitelist-path
             host))
       (ANY "/whitelist/host/:host/:flag" [host flag]
            (whitelist/whitelist-host-flag-service
             cache
             curator
             zk-whitelist-path
             whitelist-hostname-pred
             host
             flag))
       (ANY "/whitelist/:flag" [flag]
            (whitelist/whitelist-list-service
             cache
             flag))
       (ANY "*" req
            (ring.util.response/not-found "Not a supported endpoint.")))))
