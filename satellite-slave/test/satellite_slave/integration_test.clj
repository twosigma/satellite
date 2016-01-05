(ns satellite-slave.integration-test
  (use clojure.test)
  (:import (com.containersol.minimesos MesosCluster)
           (com.github.dockerjava.api DockerClient)
           (com.containersol.minimesos.mesos ClusterArchitecture
                                             ClusterArchitecture$Builder
                                             MesosMaster
                                             ClusterContainers$Filter)
           (com.github.dockerjava.api DockerClient)
           (com.github.dockerjava.core DockerClientConfig
                                       DockerClientBuilder)
           (java.util.function Function)
            ))

(defn docker-client
  []
  (-> (DockerClientConfig/createDefaultConfigBuilder)
      (.withUri "https://192.168.99.100:2376")
      (.withDockerCertPath "/Users/andalucien/.docker/machine/machines/minimesos")
      .build
      DockerClientBuilder/getInstance
      .build
      )
  )

(defn cluster-architecture
  []
  (-> (docker-client)
      ClusterArchitecture$Builder.
      .withZooKeeper
      (.withMaster (foo))
      (.withSlave "ports(*):[9200-9200,9300-9300]")
      .build)
  )

(defn new-cluster
  []
  (MesosCluster. (cluster-architecture))
  )

(defn mesos-master
  [docker, zk]
  (proxy [MesosMaster] [docker, zk])
  )

(defn foo
  []
  (reify Function
    (apply [this zk]
      (mesos-master (docker-client) zk)
      ))
  )

;; (def cluster nil)

;; (defn start-cluster
;;   [f]
;;   ;; (def cluster (com.containersol.minimesos.MesosCluster. nil))
;;   ;;  (def cluster (String. "asidojasd"))
;; ;;  (def cluster (com.containersol.minimesos.MesosCluster))
;;   )


;; public class MesosClusterTest {
;;                                @ClassRule
;;                                public static MesosCluster cluster = new MesosCluster(new ClusterArchitecture.Builder()
;;                                                                                          .withZooKeeper()
;;                                                                                          .withMaster()
;;                                                                                          .withSlave("ports(*):[9200-9200,9300-9300]")
;;                                                                                          .withSlave("ports(*):[9201-9201,9301-9301]")
;;                                                                                          .withSlave("ports(*):[9202-9202,9302-9302]")
;;                                                                                          .build());

;;                                @Test
;;                                public void mesosClusterCanBeStarted() throws Exception {
;;                                                                                         JSONObject stateInfo = cluster.getStateInfoJSON();
;;                                                                                         Assert.assertEquals(3, stateInfo.getInt("activated_slaves"));
;;                                                                                         Assert.assertTrue(cluster.getMesosMasterURL().contains(":5050"));
;;                                                                                         }
;;                                }

;;     com.containersol.minimesos.MesosCluster
;; public static final String MINIMESOS_HOST_DIR_PROPERTY = "minimesos.host.dir";

