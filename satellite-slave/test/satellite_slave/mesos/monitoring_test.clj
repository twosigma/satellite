(ns satellite-slave.mesos.monitoring-test
  (use satellite-slave.mesos.monitoring
       clojure.test)
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]))

(def metrics-snapshot
  (-> "metrics_snapshot.json" io/resource io/file slurp cheshire.core/parse-string))

(deftest reading-monitoring-metrics
  (testing "simple metrics"
    (is (= (num-tasks-failed metrics-snapshot) 21))
    (is (= (num-tasks-finished metrics-snapshot) 9)))

  (testing "total task metrics"
    (is (= (num-tasks-started metrics-snapshot) 50))))
