(ns satellite.util
  (require [clj-http.client :as client]))

(defonce thread-counter (java.util.concurrent.atomic.AtomicLong.))
(defmacro thread
  "Runs the body in a new thread"
  [& body]
  `(.start (Thread. (fn* [] ~@body)
                    (str "satellite-"
                         (.getAndIncrement
                          ^java.util.concurrent.atomic.AtomicLong thread-counter)))))
