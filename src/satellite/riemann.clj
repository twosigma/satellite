;; This namespace is a lightly modified version of Riemann whose project can be
;; found here: https://github.com/aphyr/riemann
;;
;; The modification changes the startup of Riemann are limited to
;; 1. Not initializing now redundant logging
;; 2. Including Satellite recipes in the rieman.config so they are available
;;    from the user-specified Riemann config
;; 3. Modify the riemann.core/transition! funtion to not use pmap when starting;
;;    the reason for this is that we depend on many Riemann Services that depend
;;    on another Riemann Service and if you have few processors, the call to
;;    pmap can block
;;

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
