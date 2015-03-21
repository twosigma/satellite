;; quick helper time functions, unit of time is milliseconds
(ns satellite.time)

(defn millis
  [ms]
  ms)

(defn seconds
  [secs]
  (* 1000 secs))

(defn minutes
  [mins]
  (* mins (seconds 60)))

(defn hours
  [hrs]
  (* hrs (minutes 60)))
