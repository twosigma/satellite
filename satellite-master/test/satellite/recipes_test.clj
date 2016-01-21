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

(ns satellite.recipes-test
  (use satellite.recipes
       clojure.test))

(deftest test-exceeds-threshold?

  (testing "Excessive value returns true"
    (is (exceeds-threshold? {:metric 5} {:metric 2} 2)))

  (testing "Sufficiently low ratio returns false"
    (is (not (exceeds-threshold? {:metric 3} {:metric 2} 2))))

  (testing "Positive divisor and zero dividend returns true"
    (is (exceeds-threshold? {:metric 3} {:metric 0} 2)))

  (testing "Zero divisor and zero dividend returns false"
    (is (not (exceeds-threshold? {:metric 0} {:metric 0} 2)))))


(deftest test-fold-blackhole-thresholds

  (def settings {:blackhole-fails-to-starts-threshold 0.75
                 :blackhole-fails-to-finishes-threshold 3})

  (testing "critical state when too many failures "
    (is (= "critical"
           (:state (fold-blackhole-thresholds settings
                                              [{:metric 8} {:metric 10} {:metric 2}])))))

  (testing "ok state when few failures"
    (is (= "ok"
           (:state (fold-blackhole-thresholds settings
                                              [{:metric 1} {:metric 10} {:metric 2}])))))

  (testing "ok state when only fails per start exceeeded"
    (is (= "ok"
           (:state (fold-blackhole-thresholds settings
                                              [{:metric 8} {:metric 10} {:metric 9}])))))

  (testing "ok state when only fails per finish exceeeded"
    (is (= "ok"
           (:state (fold-blackhole-thresholds settings
                                              [{:metric 8} {:metric 20} {:metric 2}])))))

  )
