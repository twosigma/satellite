(ns satellite.whitelist-test
  (use satellite.whitelist
       satellite.zk-test
       clojure.test)
  (import org.apache.curator.retry.BoundedExponentialBackoffRetry
          org.apache.curator.framework.CuratorFramework
          org.apache.curator.framework.CuratorFrameworkFactory
          org.apache.curator.framework.recipes.cache.PathChildrenCache
          org.apache.curator.test.TestingServer))

(deftest test-cache
  (with-zk [zk]
    (with-curator [zk curator]
      (.start curator)
      (.. curator create (forPath "/test" (.getBytes "test")))
      (let [state (atom 0)
            yoyo (batch-sync curator "/test" 1000 (fn [_] (swap! state inc)))]
        (.. curator create (forPath "/test/foo" (.getBytes "foo")))
        (testing "batch-sync"
          (Thread/sleep 2000)
          (is (= 1 @state))
          (.. curator create (forPath "/test/bar" (.getBytes "bar")))
          (is (= 1 @state))
          (Thread/sleep 1100)
          (is (= 2 @state)))
        (testing "get-hosts"
          (.. curator create (forPath "/test/host" (.getBytes (str :on))))
          (Thread/sleep 1000)
          (is (= #{"host"} (get-on-hosts (:cache yoyo)))))
        (testing "get-host"
          (is (= :on (get-host (:cache yoyo) "host")))
          (is (= nil (get-host (:cache yoyo) "host-not-here"))))
        ((:sync yoyo))
        (.close (:cache yoyo))))))

(deftest test-write-out-cache
  (with-zk [zk]
    (with-curator [zk curator]
      (let [cache (PathChildrenCache. curator "/test" true)]
        (.start curator)
        (.start cache)
        (testing "Empty dir"
          (.. curator create (forPath "/test" (byte-array 0)))
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr cache)
            (is (= (.toString wtr)
                   ""))))
        (testing "Non-empty dir, singleton :on"
          (.. curator create (forPath "/test/foo" (.getBytes (str :on))))
          (Thread/sleep 500)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr cache)
            (is (= (.toString wtr)
                   "foo\n"))))
        (testing "Non-empty dir, multiple :on"
          (.. curator create (forPath "/test/bar" (.getBytes (str :on))))
          (Thread/sleep 500)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr cache)
            (is (= (.toString wtr)
                   "bar\nfoo\n"))))
        (testing "Turning a host :off"
          (.. curator setData (forPath "/test/bar" (.getBytes (str :off))))
          (.. curator create (forPath "/test/baz" (.getBytes (str :on))))
          (Thread/sleep 500)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr cache)
            (is (= (.toString wtr)
                   "baz\nfoo\n"))))
        (testing "Turning another host :off"
          (.. curator setData (forPath "/test/baz" (.getBytes (str :off))))
          (Thread/sleep 500)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr cache)
            (is (= (.toString wtr)
                   "foo\n"))))
        (.close cache)))))

(deftest initialize-whitelist-test
  (with-zk [zk]
    (with-curator [zk curator]
      (.start curator)
      (try
        (initialize-whitelist (java.io.BufferedReader. (java.io.StringReader. "my\nfoo\nhosts\n")) curator "/test")
        (testing "whole"
          (is (= (read-string (String. (.. curator getData (forPath "/test/my"))))
                 :on)))))))

(deftest update-host-test
  (with-zk [zk]
    (with-curator [zk curator]
      (.start curator)
      (.. curator create (forPath "/test" (byte-array 0)))
      (testing "add host that doesn't exist"
        (update-host :on curator "/test" "my.dope.host")
        (is (= (read-string (String. (.. curator getData (forPath "/test/my.dope.host"))))
               :on)))
      (testing "change host that does exist, and use a slash dir"
        (update-host :critical curator "/test/" "my.dope.host")
        (is (= (read-string (String. (.. curator getData (forPath "/test/my.dope.host"))))
               :critical))))))
