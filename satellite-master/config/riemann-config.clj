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


;; default emailer uses localhost
(def email (mailer))
(def indx (index))

;; expire expired events every 5 seconds
(periodically-expire 5)

(streams
 indx
 (where (service #"satellite.*")
        prn)

 ;; if we stop receiving a test from a host, remove
 ;; that host from the whitelist
 (where (service #"mesos/slave.*")
        prn
        (where* expired?
                #(off-host (:host %))
                (else
                 (ensure-all-tests-on-whitelisted-host-pass))))

 ;; if less than 70% of hosts registered with mesos are
 ;; on the whitelist, alert with an email
 (where (and (service #"mesos/prop-available-hosts")
             (< metric 0.7))
        (email "foo@example.com"))

 ;; if more than 10 hosts (net) have gone down in the last
 ;; five minutes, alert with an email
 (where (service #"satellite host count")
        (coalesce (* 60 5)
                  prn
                  (where* (fn [es] (< (:metric (folds/sum es)) 10)
                          (email "foo@example.com"))))))
