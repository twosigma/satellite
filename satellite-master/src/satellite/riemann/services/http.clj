(ns satellite.riemann.services.http
  (:require [clojure.tools.logging :as log]
            [qbits.jet.server]
            [riemann.service :refer (Service ServiceEquiv)]
            [ring.middleware.params :refer (wrap-params)]
            [satellite.services :as services]))

(defrecord HTTPService
           [handler-opts host port core server]
  ServiceEquiv
  (equiv? [this other]
    (and (instance? HTTPService other)
         (= handler-opts (:handler-opts other))
         (= host (:host other))
         (= port (:port other))))
  Service
  (conflict? [this other]
    (and (instance? HTTPService other)
         (= handler-opts (:handler-opts other))
         (= host (:host other))
         (= port (:port other))))
  (reload! [this new-core]
    (reset! core new-core))
  (start! [this]
    (locking this
      (when-not @server
        (let [{:keys [curator riak riak-conn syncer manual-syncer
                      whitelist-hostname-pred
                      zk-whitelist-path]} handler-opts
              curator @curator
              whitelist-cache (:cache @syncer)
              manual-cache (:cache @manual-syncer)
              handler (services/service
                       {:bucket (:bucket riak)
                        :whitelist-cache whitelist-cache
                        :manual-cache manual-cache
                        :curator curator
                        :zk-whitelist-path zk-whitelist-path
                        :riak-conn riak-conn
                        :whitelist-hostname-pred whitelist-hostname-pred})]
          (future
            (try
              (reset! server
                      (qbits.jet.server/run-jetty
                       {:port port
                        :host (or host "127.0.0.1")
                        :ring-handler (-> handler
                                          wrap-params)
                        :join? false
                        :max-threads 10}))
              (catch Throwable t
                (log/error t "http-kit failed")
                (System/exit 17))))))))
  (stop! [this]
    (locking this
      (@server))))

(defn http-service
  [handler-opts host port]
  (HTTPService. handler-opts host port (atom nil) (atom nil)))
