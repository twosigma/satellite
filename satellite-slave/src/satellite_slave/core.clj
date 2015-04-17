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

(ns satellite-slave.core
  (:require [chime :refer [chime-at]]
            [clojure.core.async :as async]
            [clj-logging-config.log4j :as log4j-conf]
            [clojure.java.shell :as sh]
            [clojure.test]
            [clojure.tools.logging :as log]
            [satellite-slave.config :as config])
  (:use riemann.client)
  (:import java.util.concurrent.TimeUnit)
  (:gen-class))

(defn check-output
  "Evaluate the output of the command.

  Arguments:
      actual: string or integer
      expected: string, integer, or function

  Output:
      if expected is function: Output of application
      else: bool"
  [actual expected]
  (if (clojure.test/function? expected)
    (expected actual)
    (= expected actual)))

;; https://gist.github.com/asimjalis/1083188
(defn sh-timeout
  "Run a shell command but with a timeout"
  [timeout-in-seconds & args]
  (.get
   (future-call #(apply sh/sh args))
   timeout-in-seconds
   (java.util.concurrent.TimeUnit/SECONDS)))

(defn run-test
  "Keep only kv's specified in the test :output map. Return the result of the
  :output transformation.

  Arguments:
      test: test-map

  Output:
      map: With optional keys, :out :exit :err"
  [t]
  (let [cmd (if (instance? String (:command t))
              (clojure.string/split (:command t) #"\s+")
              (:command t))]
    (try
      (let [output (apply sh-timeout (or (:timeout t) 10) cmd)
            expected-m (:output t)
            expected-ks (keys expected-m)]
        (reduce
         (fn [acc k]
           (update-in acc [k] check-output (expected-m k)))
         (select-keys output expected-ks)
         expected-ks))
      (catch java.util.concurrent.TimeoutException ex
        {:timed-out true}))))

(def typical-riemann-key?
  #{:host :service :state :time
    :description :tags :metric :ttl})

(defn bool?
  [x]
  (or (true? x)
      (false? x)))

(defn sensible-riemann-event
  "The default function to construct a Riemann event from the test output.

  There is default behavior for the state field in 3 cases, in priority:
    1- There is a value for :exit; if 0, then 'ok' else 'critical'
    2- :out is a boolean value; if true, then 'ok' else 'critical'
    3- :out is a (bool, number) pair; if bool, then 'ok' else 'critical'

  The default behavior for the metric field in 4 cases, in priority:
    1- If :exit is a number, then return it
    2- If :out is a nubmer, then return it
    3- If :out is a (bool, number) pair, then return number
    4- If :err is a number, then return it

  Arguments:
      riemann: riemann-event
      test-output:

  Output:
      riemann-event, possibly with metric and state"
  [riemann test-output]
  (if (:timed-out test-output)
    (assoc riemann :state "critical")
    (let [default-pair? (fn [x] (and (coll? x)
                                     (= (count x) 2)
                                     (map #(% %2) [bool? number?] x)))
          state-fn (fn [riemann test-output]
                     (cond
                      (:exit test-output) (assoc riemann
                                            :state
                                            (if (zero? (:exit test-output))
                                              "ok"
                                              "critical"))
                      (bool? (:out test-output)) (assoc riemann
                                                   :state
                                                   (if (:out test-output)
                                                     "ok"
                                                     "critical"))
                      (default-pair? (:out test-output))
                      (assoc riemann
                        :state
                        (if (first (:out test-output))
                          "ok"
                          "critical"))
                      :else riemann))
          metric-fn (fn [riemann test-output]
                      (let [metric
                            (cond
                             (number? (:exit test-output)) (:exit test-output)
                             (number? (:out test-output)) (:out test-output)
                             (default-pair? (:out test-output))
                             (assoc riemann :metric (second (:out test-output)))
                             (number? (:err test-output)) (:err test-output))]
                        (if metric
                          (assoc riemann :metric metric)
                          riemann)))]
      (-> riemann
          (state-fn test-output)
          (metric-fn test-output)))))

(defn eventify
  "Construct a Riemann event, either using the user specified function
  corresponding to :eventify or by using our default."
  [riemann test-output]
  (let [event (-> (if-let [event-fn (:eventify test-output)]
                    (event-fn riemann test-output)
                    (sensible-riemann-event riemann test-output)))]
    ;; make a string of each value belonging to a non-special key
    (reduce
     (fn [acc k]
       (update-in acc [k] str))
     event
     (filter (complement typical-riemann-key?)
             (keys event)))))

(defn app
  [settings finish-chan]
  (let [clients (map (fn [satellite]
                       (tcp-client satellite))
                     (:satellites settings))
        env (cond
                ;; safe-env not set or is false
                (not (:safe-env settings)) (System/getenv)
                ;; safe-env is a hash-map
                (map? (:safe-env settings)) (:safe-env settings)
                ;; safe-env is set but not a hash-map, default
                :else (merge
                 (select-keys (System/getenv)
                              ["JAVA" "http_proxy" "https_proxy"
                               "no_proxy"])
                 {"PATH" "/bin/:/usr/bin/:/sbin/:/usr/sbin/"}))]
    (doseq [{:keys [riemann test]} (:comets settings)]
      ( chime-at (:schedule test)
                 (fn [_]
                   (try
                     (let [riemann (assoc riemann
                                          :time (.toSeconds TimeUnit/MILLISECONDS
                                                            (System/currentTimeMillis))
                                          :service (str (:service settings)
                                                        (:service riemann)))
                           test-output (run-test (dissoc test :schedule))
                           final-event (eventify riemann test-output)]
                       (doseq [client clients]
                         (send-event client final-event)))
                     (catch Exception ex
                       (log/error (str "service: " (:service riemann) " "
                                       "command: " (:command test))
                                  ex))))))
    (async/<!! finish-chan)))

(defn init-logging
  []
  (log4j-conf/set-loggers! (org.apache.log4j.Logger/getRootLogger)
                           {:out (org.apache.log4j.DailyRollingFileAppender.
                                  (org.apache.log4j.PatternLayout.
                                   "%d{ISO8601} %-5p %c [%t] - %m%n")
                                  "log/satellite-slave.log"
                                  "'.'yyyy-MM-dd")
                            :level :info}))

(defn -main
  [& [config args]]
  (init-logging)
  (log/info "Starting Satellite-Slave")
  (if (and config
           (.exists (java.io.File. config)))
    (do (log/info (str "Reading config from file: " config))
        (config/include config))
    (log/info (str "Using default settings.")))
  (let [finish-chan (async/chan 1)]
    (app config/settings finish-chan)))
