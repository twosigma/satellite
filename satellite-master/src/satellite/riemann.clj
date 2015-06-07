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

;; This namespace modifies the startup of Riemann whose project can be found
;; here: https://github.com/aphyr/riemann
;;
;; The modification changes the startup of Riemann are limited to
;; 1. Not initializing now redundant logging
;; 2. Including Satellite recipes in the rieman.config so they are available
;;    from the user-specified Riemann config
;; 3. Modify the riemann.core/transition! funtion to not use pmap when starting;
;;    the reason for this is that we depend on many Riemann Services that depend
;;    on another Riemann Service and if you have few processors, the call to
;;    pmap can block
;; 4. Remove handling for reloading the config. Currently the rest of Satellite
;;    requires a hard reset. However, a big TODO is to make everything
;;    reloadable.

(ns satellite.riemann
  (:require [riemann.config]
            riemann.logging
            riemann.time
            [riemann.test :as test]
            riemann.pubsub
            satellite.util)
  (:use clojure.tools.logging))

(in-ns 'riemann.core)

(defn transition!
  "A core transition \"merges\" one core into another. Cores are immutable,
  but the stateful resources associated with them aren't. When you call
  (transition! old-core new-core), we:

  1. Stop old core services without an equivalent in the new core.

  2. Merge the new core's services with equivalents from the old core.

  3. Reload all services with the merged core.

  4. Start all services in the merged core.

  Finally, we return the merged core. old-core and new-core can be discarded.

  NB: This function is modified from the original at *"
  [old-core new-core]
  (let [merged (merge-cores old-core new-core)
        old-services (set (core-services old-core))
        merged-services (set (core-services merged))]

    ; Stop old services
    (dorun (pmap service/stop!
                 (clojure.set/difference old-services merged-services)))

    ; Reload merged services
    (dorun (pmap #(service/reload! % merged) merged-services))

    ; * Start merged services
    (dorun (map #(future (service/start! %)) merged-services))

    (info "Hyperspace core online")
    merged))

(in-ns 'satellite.riemann)

(defn start-riemann
  "Start Riemann. Loads a configuration file from the first of its args."
  [config]
  (try
    (riemann.time/start!)
    (binding [*ns* (find-ns 'riemann.config)]
      (require '[satellite.whitelist :as whitelist])
      (require '[satellite.recipes :refer :all]))
    (riemann.config/include config)
    (riemann.config/apply!)
    nil))
