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

(deftest merge-whitelist-caches!-test
  (with-zk [zk]
    (with-curator [zk curator]
      (.start curator)
      (doseq [path ["/whitelist" "/manual" "/managed"]]
        (.. curator create (forPath path (byte-array 0))))
      (let [[whitelist-state manual-state managed-state]
            (map (fn [x] (atom 0)) (range 3))
            whitelist (batch-sync curator "/whitelist" 100
                                  (fn [_] (swap! whitelist-state inc)))
            manual (batch-sync curator "/manual" 100
                               (fn [_] (swap! manual-state inc)))
            managed (batch-sync curator "/managed" 100
                                (fn [_] (swap! managed-state inc)))]
        (testing "basics"
          (.. curator create (forPath "/manual/foo" (.getBytes (str :on))))
          (.. curator create (forPath "/manual/baz" (.getBytes (str :on))))
          (.. curator create (forPath "/managed/bar" (.getBytes (str :on))))
          (.. curator create (forPath "/managed/baz" (.getBytes (str :off))))
          (Thread/sleep 1000)
          (merge-whitelist-caches! curator "/whitelist"
                                   (:cache whitelist)
                                   (:cache managed)
                                   (:cache manual))
          (Thread/sleep 1000)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr (:cache whitelist))
            (is (= (.toString wtr)
                   "bar\nbaz\nfoo\n")))))))
  (with-zk [zk]
    (with-curator [zk curator]
      (.start curator)
      (doseq [path ["/whitelist" "/manual" "/managed"]]
        (.. curator create (forPath path (byte-array 0))))
      (let [[whitelist-state manual-state managed-state]
            (map (fn [x] (atom 0)) (range 3))
            whitelist (batch-sync curator "/whitelist" 100
                                  (fn [_] (swap! whitelist-state inc)))
            manual (batch-sync curator "/manual" 100
                               (fn [_] (swap! manual-state inc)))
            managed (batch-sync curator "/managed" 100
                                (fn [_] (swap! managed-state inc)))]
        (testing "no managed list"
          (doseq [[path flag] (->> (interleave (repeat :on) (repeat :off))
                                   (map vector (map char (range 97 107))))]
            (.. curator create (forPath (str "/manual/" path) (.getBytes (str flag)))))
          (Thread/sleep 1000)
          (merge-whitelist-caches! curator "/whitelist"
                                   (:cache whitelist)
                                   (:cache managed)
                                   (:cache manual))
          (Thread/sleep 1000)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr (:cache whitelist))
            (is (= (.toString wtr)
                   "a\nc\ne\ng\ni\n"))))
        (testing "adding a managed list that is all overridden"
          (doseq [[path flag] (->> (repeat :off)
                                   (map vector (map char (range 97 107))))]
            (.. curator create (forPath (str "/managed/" path) (.getBytes (str flag)))))
          (Thread/sleep 1000)
          (merge-whitelist-caches! curator "/whitelist"
                                   (:cache whitelist)
                                   (:cache managed)
                                   (:cache manual))
          (Thread/sleep 1000)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr (:cache whitelist))
            (is (= (.toString wtr)
                   "a\nc\ne\ng\ni\n")))))))
  (with-zk [zk]
    (with-curator [zk curator]
      (.start curator)
      (doseq [path ["/whitelist" "/manual" "/managed"]]
        (.. curator create (forPath path (byte-array 0))))
      (let [[whitelist-state manual-state managed-state]
            (map (fn [x] (atom 0)) (range 3))
            whitelist (batch-sync curator "/whitelist" 100
                                  (fn [_] (swap! whitelist-state inc)))
            manual (batch-sync curator "/manual" 100
                               (fn [_] (swap! manual-state inc)))
            managed (batch-sync curator "/managed" 100
                                (fn [_] (swap! managed-state inc)))]
        (testing "start with nothing"
          (Thread/sleep 1000)
          (merge-whitelist-caches! curator "/whitelist"
                                   (:cache whitelist)
                                   (:cache managed)
                                   (:cache manual))
          (Thread/sleep 1000)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr (:cache whitelist))
            (is (= (.toString wtr)
                   ""))))
        (testing "adding a managed list that is half on"
          (doseq [[path flag] (->> (interleave (repeat :on) (repeat :off))
                                   (map vector (map char (range 97 107))))]
            (.. curator create (forPath (str "/managed/" path) (.getBytes (str flag)))))
          (Thread/sleep 1000)
          (merge-whitelist-caches! curator "/whitelist"
                                   (:cache whitelist)
                                   (:cache managed)
                                   (:cache manual))
          (Thread/sleep 1000)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr (:cache whitelist))
            (is (= (.toString wtr)
                   "a\nc\ne\ng\ni\n"))))
        (testing "override everything"
          (doseq [[path flag] (->> (repeat :on)
                                   (map vector (map char (range 97 107))))]
            (.. curator create (forPath (str "/manual/" path) (.getBytes (str flag)))))
          (Thread/sleep 1000)
          (merge-whitelist-caches! curator "/whitelist"
                                   (:cache whitelist)
                                   (:cache managed)
                                   (:cache manual))
          (Thread/sleep 1000)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr (:cache whitelist))
            (is (= (.toString wtr)
                   "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n"))))
        (testing "manually override first half to off"
          (doseq [[path flag] (->> (repeat :off)
                                   (map vector (map char (range 97 102))))]
            (.. curator setData (forPath (str "/manual/" path) (.getBytes (str flag)))))
          (Thread/sleep 1000)
          (merge-whitelist-caches! curator "/whitelist"
                                   (:cache whitelist)
                                   (:cache managed)
                                   (:cache manual))
          (Thread/sleep 1000)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr (:cache whitelist))
            (is (= (.toString wtr)
                   "f\ng\nh\ni\nj\n"))))
        (testing "remove manual override for first half"
          (doseq [path (map char (range 97 102))]
            (.. curator delete (forPath (str "/manual/" path))))
          (Thread/sleep 1000)
          (merge-whitelist-caches! curator "/whitelist"
                                   (:cache whitelist)
                                   (:cache managed)
                                   (:cache manual))
          (Thread/sleep 1000)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr (:cache whitelist))
            (is (= (.toString wtr)
                   "a\nc\ne\nf\ng\nh\ni\nj\n"))))))))
