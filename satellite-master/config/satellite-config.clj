(ns satellite.core)

(def settings
  (merge settings
    {:mesos-master-url
     (fnk [] (url/url "http://localhost:5050"))
     :riak (fnk [] nil)
     :sleep-time (fnk [] 5000)
     :zookeeper (fnk [] "localhost:2181")
     }))
