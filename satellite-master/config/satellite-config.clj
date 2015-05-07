(ns satellite.core)

(def settings
  (merge settings
         {:mesos-master-url (url/url "http://localhost:5050")
          :sleep-time 5000
          :zookeeper "localhost:2181"
          :service-host "0.0.0.0"}))
