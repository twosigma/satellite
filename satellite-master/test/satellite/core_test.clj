(ns satellite.core-test
  (use clojure.test satellite.core))

(deftest test-clj-config
  (testing "settings are updated via .clj file"
    (is (= 1 (load-settings "config/satellite-config.clj")))
    )
  )

(deftest test-edn-config
  (testing "settings are updated via .clj file"
    (is (= 1 (load-settings "config/satellite-config.edn")))
    )
  )

