;; This namespace is a lightly modified version of Riemann whose project can be
;; found here: https://github.com/aphyr/riemann
;;
;; The modification changes the startup of Riemann are limited to
;; 1. Not initializing now redundant logging
;; 2. Including Satellite recipes in the rieman.config so they are available
;;    from the user-specified Riemann config

(ns satellite.riemann
  (:require [riemann.config]
            riemann.logging
            riemann.time
            [riemann.test :as test]
            riemann.pubsub
            satellite.util)
  (:use clojure.tools.logging))

(def config-file
  "The configuration file loaded by the bin tool"
  (atom nil))

(def reload-lock (Object.))

(defn reload!
  "Reloads the given configuration file by clearing the task scheduler, shutting
  down the current core, and loading a new one."
  []
  (locking reload-lock
    (try
      (riemann.config/validate-config @config-file)
      (riemann.time/reset-tasks!)
      (riemann.config/clear!)
      (riemann.pubsub/sweep! (:pubsub @riemann.config/core))
      (riemann.config/include @config-file)
      (riemann.config/apply!)
      :reloaded
      (catch Exception e
        (error e "Couldn't reload:")
        e))))

(defn handle-signals
  "Sets up POSIX signal handlers."
  []
  (if (not (.contains (. System getProperty "os.name") "Windows"))
    (sun.misc.Signal/handle
     (sun.misc.Signal. "HUP")
     (proxy [sun.misc.SignalHandler] []
       (handle [sig]
         (info "Caught SIGHUP, reloading")
         (reload!))))))
(defn pid
  "Process identifier, such as it is on the JVM. :-/"
  []
  (let [name (-> (java.lang.management.ManagementFactory/getRuntimeMXBean)
                 (.getName))]
    (try
      (get (re-find #"^(\d+).*" name) 1)
      (catch Exception e name))))

(defn start-riemann
  "Start Riemann. Loads a configuration file from the first of its args."
  [config]
  (try
    (info "PID" (pid))
    (reset! config-file config)
    (handle-signals)
    (riemann.time/start!)
    (binding [*ns* (find-ns 'riemann.config)]
      (require '[satellite.whitelist :as whitelist])
      (require '[satellite.recipes :refer :all]))
    (riemann.config/include @config-file)
    (riemann.config/apply!)
    nil
    (catch Exception e
      (error e "Couldn't start"))))
