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

(ns satellite.riemann.services.leader
  (:require [clj-http.client :as client]
            [clojure.tools.logging :as log]
            [riemann.service :refer (Service ServiceEquiv)]
            [riemann.instrumentation :as instrumentation]
            [satellite.time :as time]))

(defrecord LeaderService
           [mesos-master-url core leader leader? watcher close]
  ServiceEquiv
  (equiv? [this other]
    (and (instance? LeaderService other)
         (= mesos-master-url (:mesos-master-url other))))
  Service
  (conflict? [this other]
    (and (instance? LeaderService other)
         (= mesos-master-url (:mesos-master-url other))))
  (reload! [this new-core]
    (reset! core new-core))
  (start! [this]
    (log/info "Starting leader service")
    (locking this
      (when-not @watcher
        (let [close (:close this)
              t (Thread. (fn []
                           (loop []
                             (when-not @close
                               (try
                                 (let [response (client/get
                                                 (str mesos-master-url "/master/state.json")
                                                 {:as :json-string-keys})
                                       leader-pid (get-in response [:body "leader"])
                                       pid (get-in response [:body "pid"])]
                                   (reset! leader (= leader-pid pid)))
                                 (catch Exception ex
                                   (log/error (str "Requesting state.json failed from: "
                                                   mesos-master-url)
                                              ex)))
                               (try
                                 (Thread/sleep (-> 1 time/seconds))
                                 (catch Exception ex
                                   (log/error ex)))
                               (recur))))
                         "leader-watcher")
              leader?* (fn []
                         @leader)]
          (alter-var-root (var riemann.config/leader?)
                          (fn [leader?]
                            leader?*))
          (deliver leader? leader?*)
          (reset! watcher t)
          (.start t))))
    (log/info "Leader service started"))
  (stop! [this]
    (reset! close true)))

(defn leader-service
  [mesos-master-url]
  (let [leader (atom nil)]
    (alter-var-root (var riemann.core/stream!)
                    (fn [stream!]
                      (if (:stream!! (meta stream!))
                        stream!
                        (with-meta
                          (fn [core event]
                            (when @leader
                              (stream! core event)))
                          (assoc (meta stream!)
                                 :stream!! stream!)))))
    (LeaderService. mesos-master-url (atom nil) leader
                    (promise) (atom nil) (atom nil))))
