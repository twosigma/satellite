;; quick helper time functions, unit of time is milliseconds
(ns satellite.time)

(defn seconds
  [ms]
  (* 1000 ms))

(defn minutes
  [ms]
  (* ms (seconds 60)))

(defn hours
  [ms]
  (* ms (minutes 60)))
