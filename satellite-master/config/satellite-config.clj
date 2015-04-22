(ns satellite.core)

(def settings
  (merge settings
    {:mesos-master-url
     (fnk [] (url/url "http://10.165.223.247:5050"))
     :riak (fnk [] nil)
     :sleep-time (fnk [] 5000)
     :zookeeper (fnk [] "10.176.0.79:2181")
     }))
