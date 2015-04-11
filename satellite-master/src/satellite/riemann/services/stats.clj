(ns satellite.riemann.services.stats
  (:require [riemann.config]))

(defrecord StatsHTTPService
    [core stats]
  ServiceEquiv
  (equiv? [this other]
    (instance? StatsHTTPService other))
  Service
  (conflict? [this other]
    (instance? StatsHTTPService other))
  (reload! [this new-core]
    (reset! core new-core))
  (start! [this]
    (locking this
      (when-not @stats
        (reset! stats {})
        (let [event-put (fn [event]
                          (if (riemann.config/expired? event)
                            (swap! stats dissoc
                                   (:service event))
                            (swap! stats assoc
                                   (:service event)
                                   (:metric event))))]
          (intern 'riemann.config
                  'stats-put*
                  (fn [k v] (swap! stats assoc k v)))
          (intern 'riemann.config
                  'stats-get*
                  (fn [k] (get @stats k)))
          (intern 'riemann.config
                  'stats-put
                  event-put)))))
  (stop! [this]
    (locking this)))

(defn stats-service
  []
  (StatsHTTPService. (atom nil) (atom nil)))
