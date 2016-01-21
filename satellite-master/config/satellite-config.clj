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

(ns satellite.core)

;; Double check the location of your Mesos whitelist file. By default it
;; should be in /etc/mesos-master. Override local-whitelist-path if it's 
;; located anywhere else.

;; If you are going to run Satellite in a cluster, you will need to set
;; riemann-tcp-server-options. By default, it's set to localhost and will
;; only allow connections from localhost.

{:mesos-master-url (url/url "http://localhost:5050")
 :sleep-time 5000
 :zookeeper "localhost:2181"
 :local-whitelist-path "/etc/mesos/whitelist"
 :riemann-tcp-server-options {:host "localhost" }
 :service-host "0.0.0.0"}
