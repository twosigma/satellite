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
  (require [clojure.core.async :as async])
  (import org.apache.curator.retry.BoundedExponentialBackoffRetry
          org.apache.curator.framework.CuratorFramework
          org.apache.curator.framework.CuratorFrameworkFactory
          org.apache.curator.framework.recipes.cache.PathChildrenCache
          org.apache.curator.test.TestingServer))

(def empty-whitelist-data (new-whitelist-data))
(def managed-on-whitelist-data (set-flag empty-whitelist-data :managed "on"))
(def managed-off-whitelist-data (set-flag empty-whitelist-data :managed "off"))
(def manual-on-whitelist-data (set-flag empty-whitelist-data :manual "on"))
(def manual-off-whitelist-data (set-flag empty-whitelist-data :manual "off"))

(deftest test-whitelist-data-on?
  (testing "test-1"
    (let [whitelist-data empty-whitelist-data]
      (is (= false (whitelist-data-on? whitelist-data)))))
  (testing "test-2"
    (let [whitelist-data (set-flag empty-whitelist-data :managed "on")]
      (is (= true (whitelist-data-on? whitelist-data)))))
  (testing "test-3"
    (let [whitelist-data (set-flag empty-whitelist-data :manual "off")]
      (is (= false (whitelist-data-on? whitelist-data)))))
  (testing "test-4"
    (let [whitelist-data (-> empty-whitelist-data
                             (set-flag :managed "off")
                             (set-flag :manual "on"))]
      (is (= true (whitelist-data-on? whitelist-data)))))
  (testing "test-5"
    (let [whitelist-data (-> empty-whitelist-data
                             (set-flag :managed "on")
                             (set-flag :manual "off"))]
      (is (= false (whitelist-data-on? whitelist-data))))))

(deftest test-cache
  (with-zk [zk]
    (with-curator [zk curator]
      (.start curator)
      (.. curator create (forPath "/test" (.getBytes "test")))

      (with-resources [cache (start-cache! curator "/test" (async/chan (async/sliding-buffer 1)))]
        (testing "get-hosts"
          (let [whitelist-data (set-flag empty-whitelist-data :managed "on")]
            (.. curator create (forPath "/test/host" (whitelist-data->bytes whitelist-data)))
            (Thread/sleep 1000)
            (is (= #{"host"} (set (keys (get-on-hosts cache)))))
            (is (= whitelist-data (:whitelist-data (get-host cache "/test" "host"))))
            (is (= nil (:whitelist-data (get-host cache "/test" "host-not-here"))))))))))

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
          (.. curator create (forPath "/test/foo" (whitelist-data->bytes managed-on-whitelist-data)))
          (Thread/sleep 500)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr cache)
            (is (= (.toString wtr)
                   "foo\n"))))
        (testing "Non-empty dir, multiple :on"
          (.. curator create (forPath "/test/bar" (whitelist-data->bytes managed-on-whitelist-data)))
          (Thread/sleep 500)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr cache)
            (is (= (.toString wtr)
                   "bar\nfoo\n"))))
        (testing "Turning a host :off"
          (.. curator setData (forPath "/test/bar" (whitelist-data->bytes managed-off-whitelist-data)))
          (.. curator create (forPath "/test/baz" (whitelist-data->bytes manual-on-whitelist-data)))
          (Thread/sleep 500)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr cache)
            (is (= (.toString wtr)
                   "baz\nfoo\n"))))
        (testing "Turning another host :off"
          (.. curator setData (forPath "/test/baz" (whitelist-data->bytes manual-off-whitelist-data)))
          (Thread/sleep 500)
          (let [wtr (java.io.StringWriter.)]
            (write-out-cache! wtr cache)
            (is (= (.toString wtr)
                   "foo\n"))))
        (.close cache)))))

(deftest update-host-test
  (with-zk [zk]
    (with-curator [zk curator]
      (.start curator)
      (.. curator create (forPath "/test" (byte-array 0)))

      (with-resources [cache (path-cache curator "/test" (async/chan (async/sliding-buffer 1)))]
        (.start cache)
        (testing "test"
          (let [event1 {:state "ok" :description "test1"}
                event2 {:state "critical" :description "test2"}]
            (try
              (is (zk-update-event! curator cache "/test" "host1" :managed "event1" event1))
              (Thread/sleep 1000)
              (is (= (:whitelist-data (get-host cache "/test" "host1"))
                     {:manual-events {}
                      :managed-events {"event1" event1}
                      :managed-flag nil
                      :manual-flag nil}))
              (Thread/sleep 1000)
              (is (zk-update-event! curator cache "/test" "host1" :managed "event2" event2))
              (Thread/sleep 1000)
              (is (zk-update-event! curator cache "/test" "host1" :managed "event2" event2))
              (is (= (:whitelist-data (get-host cache "/test" "host1"))
                     {:manual-events {}
                      :managed-events {"event1" event1
                                       "event2" event2}
                      :managed-flag nil
                      :manual-flag nil}))
              (Thread/sleep 1000)
              (is (zk-update-event! curator cache "/test" "host1" :managed "event1" nil))
              (is (zk-update-event! curator cache "/test" "host1" :managed "event2" nil))
              (Thread/sleep 1000)
              (is (zk-update-event! curator cache "/test" "host1" :managed "event2" nil))
              (is (= (:whitelist-data (get-host cache "/test" "host1"))
                     {:manual-events {}
                      :managed-events {}
                      :managed-flag nil
                      :manual-flag nil
                      }))
              (is (zk-update-flag! curator cache "/test" "host1" :managed "on"))
              (Thread/sleep 1000)
              (is (= (:whitelist-data (get-host cache "/test" "host1"))
                     {:manual-events {}
                      :managed-events {}
                      :managed-flag "on"
                      :manual-flag nil
                      }))
              (is (zk-update-flag! curator cache "/test" "host1" :manual "off"))
              (Thread/sleep 1000)
              (is (= (:whitelist-data (get-host cache "/test" "host1"))
                     {:managed-events {}
                      :manual-events {}
                      :managed-flag "on"
                      :manual-flag "off"
                      })))))))))

(defn test-recompute-manual-flag
  [whitelist-data]
  (testing "test"
    (let [event1 {:state "ok" :time 0}
          event2 {:state "critical" :time 1000}]
      (let [whitelist empty-whitelist-data]
        (is (= nil (-> whitelist
                        recompute-manual-flag
                        :manual-flag))))
      (let [whitelist (set-event empty-whitelist-data :manual "event1" event1)]
        (is (= "on" (-> whitelist
                        recompute-manual-flag
                        :manual-flag))))
      (let [whitelist (set-event empty-whitelist-data :manual "event2" event2)]
       (is (= "off" (-> whitelist
                        recompute-manual-flag
                        :manual-flag))))
      (let [whitelist (-> empty-whitelist-data
                          (set-event :manual "event1" event1)
                          (set-event :manual "event2" event2))]
        (is (= "off" (-> whitelist
                         recompute-manual-flag
                         :manual-flag)))))))

(comment (run-tests))
