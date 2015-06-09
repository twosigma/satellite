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

(defproject satellite-master "0.1.0"
  :description "Monitoring and alerting for Mesos"
  :url "http://github.com/twosigma/satellite/satellite-master"
  :license {:name "Apache License, Version 2.0"
            :url "http://opensource.org/licenses/apache2.0.php"}
  :dependencies [[cc.qbits/jet "0.5.4"]
                 [cheshire "5.3.1"]
                 [clj-http "0.9.2"]
                 [clj-logging-config "1.9.12"]
                 [com.cemerick/url "0.1.1"]
                 [com.novemberain/welle "3.0.0"]
                 [compojure "1.1.6"]
                 [jarohen/chime "0.1.6"]
                 [liberator "0.12.2"]
                 [ring/ring "1.2.1"]
                 [ring/ring-core "1.3.2"]
                 [ring/ring-json "0.3.1"
                  :exclusions [ring/ring-core]]
                 [org.apache.curator/curator-framework "2.6.0"
                  :exclusions [io.netty/netty]]
                 [org.apache.curator/curator-recipes "2.6.0"
                  :exclusions [io.netty/netty]]
                 [org.apache.curator/curator-test "2.6.0"
                  :exclusions [io.netty/netty]]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.7"
                  :exclusions [log4j]]
                 [prismatic/plumbing "0.3.3"]
                 ;; do not bump past schema 0.3.3 -- broken for :aot
                 ;; check https://github.com/Prismatic/plumbing/issues/74
                 ;; for progress
                 [prismatic/schema "0.3.3"]
                 [riemann "0.2.8"]]
  :plugins [[lein-cljfmt "0.1.10"]]
  :aot [satellite.core]
  :main satellite.core
  :aliases {"release-jar" ["do" "clean," "uberjar"]})
