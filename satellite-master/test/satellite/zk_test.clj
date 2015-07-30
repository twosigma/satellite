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

(ns satellite.zk-test
  "Supports zookeeper testing."
  (:import (org.apache.curator.test TestingServer)
           (org.apache.curator.retry BoundedExponentialBackoffRetry)
           (org.apache.curator.framework CuratorFrameworkFactory)))

;; https://github.com/Factual/skuld/blob/master/test/skuld/zk_test.clj
(defmacro with-zk
  "Evaluates body with a zookeeper server running, and the connect string bound
  to the given variable. Ensures the ZK server is shut down at the end of the
  body. Example:

  (with-zk [zk-string]
  (connect-to zk-string)
  ...)"
  [[connect-string] & body]
  `(let [zk#             (TestingServer.)
         ~connect-string (.getConnectString zk#)]
     (try
       ~@body
       (finally
         (.close zk#)))))

(defmacro with-curator
  "Evaluates body with a Curator instance running, and the curator client bound
  to the given variable. Ensures the Curator is shut down at the end of the
  body. Example:

  (with-curator [zk-string curator]
  (.start curator)
  ...)"
  [[connect-string curator] & body]
  `(let [retry-policy#   (BoundedExponentialBackoffRetry. 100 120000 10)
         ~curator (CuratorFrameworkFactory/newClient ~connect-string
                                                     180000
                                                     30000
                                                     retry-policy#)]
     (try
       ~@body
       (finally
         (.close ~curator)))))

(defmacro with-resources
  [bindings & body]
  (let [[x v & more] bindings]
    `(let [~x ~v]
       (try
         ~(if-let [more (seq more)]
            `(with-resources ~more ~@body)
            `(do ~@body))
         (finally
           (.close ~x))))))
