(def mesos-work-dir "/tmp/mesos")

(def settings
  {:satellites [{:host "satellite.master.1.example.com"}
                {:host "satellite.master.2.example.com"}]
   :service "mesos/slave/"
   :comets [(satellite-slave.recipes/free-memory 50 (-> 60 t/seconds))
            (satellite-slave.recipes/free-swap   50 (-> 60 t/seconds))
            (satellite-slave.recipes/percentage-used 90 "/tmp" (-> 60 t/seconds))
            (satellite-slave.recipes/percentage-used 90 "/var" (-> 60 t/seconds))
            (satellite-slave.recipes/percentage-used 90 mesos-work-dir
                                                     (-> 60 t/seconds))
            (satellite-slave.recipes/num-uninterruptable-processes 10 (-> 60 t/seconds))
            (satellite-slave.recipes/load-average 30 (-> 60 t/seconds))
            {:command ["echo" "17"]
             :schedule (every (-> 60 t/seconds))
             :output (fn [{:keys [out err exit]}]
                       (let [v (-> out
                                   (clojure.string/split #"\s+")
                                   first
                                   (Integer/parseInt))]
                         {:state "ok"
                          :metric v
                          :ttl 300
                          :description "example test -- number of files/dirs in cwd"}))}]})
