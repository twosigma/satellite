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
            [satellite-slave.config :as config]
            [satellite-slave.mesos.monitoring :as monitor])
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
      riemann-events: list"
  [t]
  (let [cmd (if (instance? String (:command t))
              (clojure.string/split (:command t) #"\s+")
              (:command t))]
    (let [output (apply sh-timeout (or (:timeout t) 10) cmd)]
      ((:output t) output))))

(def typical-riemann-key?
  #{:host :service :state :time
    :description :tags :metric :ttl})

(defn bool?
  [x]
  (or (true? x)
      (false? x)))

(defn stringify
  "Make a string of each value belonging to a non-special key"
  [event]
  (reduce
   (fn [acc k]
     (update-in acc [k] str))
   event
   (filter (complement typical-riemann-key?)
           (keys event))))

(defn app
  [settings dry-run finish-chan]
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
    (doseq [test (:comets settings)]
      (chime-at (:schedule test)
                (fn [_]
                  (try
                    (let [riemann-events (try
                                           (run-test (dissoc test :schedule))
                                          (catch java.util.concurrent.TimeoutException ex
                                            [{:state "critical"
                                              :description "timed out"}])
                                          (catch Exception ex
                                            [{:state "critical"
                                              :description (str "command: " (:command test) ex)}]))
                          add-time-and-svc (fn [riemann-event]
                                             (assoc riemann-event
                                                    :time (.toSeconds TimeUnit/MILLISECONDS
                                                                      (System/currentTimeMillis))
                                                    :service (str (:service settings)
                                                                  (:service riemann-event))))
                          riemann-events (map add-time-and-svc riemann-events)]
                      (doseq [client clients
                              riemann-event riemann-events]
                        (try
                          (if dry-run
                            (println riemann-event)
                            (send-event client riemann-event))
                          (log/debug (format "Sent event: %s" riemann-event))
                          (catch Exception ex
                            (log/error (str "service: " (:service riemann-event) " "
                                            "command: " (:command test) ex) ex)))))
                    (catch Exception ex
                      (log/error (str "command: " (:command test) ex) ex))))))
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
  (prn "HEY HEY STARTING!!!")
  (init-logging)
  (log/info "Starting Satellite-Slave")
  (if (and config
           (.exists (java.io.File. config)))
    (do (log/info (str "Reading config from file: " config))
        (config/include config))
    (log/info (str "Using default settings.")))
  (config/validate-current-settings!)
  (log/info "Configured settings correctly follow the settings schema.")
  (let [finish-chan (async/chan 1)
        dry-run (when args (.contains args "--dry-run"))]
    (app config/settings dry-run finish-chan)))
