(ns satellite.services.stats
  (:require [clojure.tools.logging :as log]
            [liberator.core :refer (resource)]))

(def state (atom {:num-hosts-up 0
                  :num-available-hosts 0}))
(def num-hosts-up (atom 0))
(def num-available-hosts (atom 0))

(add-watch
 num-hosts-up :prop-up
 (fn [key atom old-num-up new-num-up]
   (swap! state
          (fn [state]
            (assoc state
                   :num-hosts-up new-num-up
                   :prop-available-hosts
                   (if (zero? (:num-available-hosts state))
                     (do
                       (log/warn "No available hosts")
                       0)
                     (/ new-num-up
                        (:num-available-hosts state))))))))

(add-watch
 num-available-hosts :prop-up
 (fn [key atom old-total new-total]
   (swap! state
          (fn [state]
            (assoc state
                   :num-available-hosts new-total
                   :prop-available-hosts
                   (if (zero? new-total)
                     (do
                       (log/warn "No available hosts")
                       0)
                     (/ (:num-hosts-up state)
                        new-total)))))))

(defn stats
  []
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]
   :handle-ok (fn [ctx]
                @state)))
