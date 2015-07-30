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

(ns satellite.recipes
  (:require
   [clojure.tools.logging :refer :all]
   [riemann.streams :refer :all]
   [satellite.whitelist :as whitelist]))

;; Forward declaration of on/off-host so they can be used in this namespace and
;; accesible from the user defined riemann-config. The real defintion will occur
;; at initialization. I prefer `defn' to `declare' here because in case the
;; function gets called before it is truly defined (ie, there is a race) then I
;; want to know about it.

(declare on-host)
(declare off-host)
(declare persist-event)
(declare delete-event)

(def alive?
  (complement expired?))

;; thank you to aphyr for showing us this
(defn hysteresis?
  "Returns an event predicate which tests the metric of an event and allows
  for hysteresis.  Requires two predicates to test metrics: a trigger?
  predicate and a hold? predicate.  trigger? and hold? should be defined
  such that if trigger? is true, then hold? is true (and, conversely, if
  hold? is false, then trigger? is false).
  If the metric is true for trigger?, hyesteresis? will return true.  If
  the metric returns false for hold?, hystersis? will return false.  If
  hold? is true and trigger? is false, hysteresis? will return the value
  it returned for the previous invocation of hysteresis?.  That is,
  when in the hold? state (hold? true and trigger? false), hystersis?
  holds the previous value. (hystersis? is initially assumed to be false.)
  As an example, if trigger? is defined as #(> (:metric %) threshold-hi)
  and hold? is defined as (> (:metic %) threshold-lo), then the diagram
  below demonstrated the behavior of hysteresis?

  |                     /\\
  |                    /  \\      /\\
  +-------------------/----\\--- /--\\------------ threshold-hi
  |         /\\      /      \\  /    \\
  |        /  \\    /        \\/      \\
  +-------/----\\--/-------------------\\--------- threshold-lo
  |      /      \\/                     \\
  |  ___/                                \\_____   metric
  |
  +--------------------------------------------

  FFFFFFFFFFFFFFFFFFFTTTTTTTTTTTTTTTTFF      hysteresis?

  This is useful for providing simple filtering of the metric, so that,
  for example, actions are triggered only once as a metric climbs or falls.

  Because hysteresis? relies on previous state, it is normally used within
  a (by [:host :service] ...) clause to ensure that each host and service
  gets its own copy of the predicate."
  [trigger? hold?]
  (let [trigger? (if (number? trigger?) #(> (:metric %) trigger?) trigger?)
        hold?    (if (number? hold?) #(> (:metric %) hold?) hold?)
        hyst? (fn [acc-hyst event]
                (or (trigger? event) (and (hold? event) acc-hyst)))
        acc-hyst (atom false)]
    (fn pred-stream [event]
      (when (alive? event)
        (swap! acc-hyst hyst? event))
      @acc-hyst)))

(defn cluster-alert
  [pred pd]
  (where (service #"prop-available-hosts")
         (where* pred
                 (:resolve pd)
                 (else
                  (with :state "critical"
                        (:trigger pd))))))

(defn forward-cluster-alert
  [pred client]
  (where (service #"prop-available-hosts")
         (where* pred
                 (tag "alert"
                      (forward client))
                 (else
                  (tag "resolve"
                       (forward client))))))

(defn ensure-all-tests-pass
  [es]
  (let [host (:host (first es))]
    (if-let [bad-test (some
                       (fn [e] (when-not (#{"ok"} (:state e))
                                 e))
                       es)]
      (do
        (warn "Turning off host" host
              "due to failed test:" bad-test)
        (off-host host))
      (on-host host))))
