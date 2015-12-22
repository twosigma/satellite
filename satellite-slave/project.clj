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

(defproject satellite-slave "0.2.0-SNAPSHOT"
  :description "Monitoring and alerting for Mesos"
  :url "http://github.com/twosigma/satellite/satellite-slave"
  :license {:name "Apache License, Version 2.0"
            :url "http://opensource.org/licenses/apache2.0.php"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [clj-logging-config "1.9.12"]
                 [jarohen/chime "0.1.6"]
                 [org.slf4j/slf4j-log4j12 "1.7.7"
                  :exclusions [log4j]]
                 [riemann-clojure-client "0.3.1"]
                 [com.github.ContainerSolutions/minimesos "0.5.0"
                  :exclusions [org.slf4j/log4j-over-slf4j
                               ch.qos.logback/logback-core
                               ch.qos.logback/logback-classic]
                 [clj-http "1.1.0"]]
  :plugins [[lein-cljfmt "0.1.10"]]
  :aot [satellite-slave.core]
  :main satellite-slave.core
  :resource-paths ["resources"]
  :repositories {"jitpack" "https://jitpack.io"}
  :aliases {"release-jar" ["do" "clean," "uberjar"]})
