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
           (satellite_slave my_mesos_master)
            ))

(defn docker-client
  []
  (-> (DockerClientConfig/createDefaultConfigBuilder)
      (.withUri "https://192.168.99.101:2376")
      (.withDockerCertPath "/Users/andalucien/.docker/machine/machines/minimesos")
      .build
      DockerClientBuilder/getInstance
      .build
      )
  )

(defn mesos-master
  [docker, zk]
  (new satellite_slave.my_mesos_master nil nil)

  (proxy [MesosMaster] [docker, zk]
    (dockerCommand []
      (prn this)
      (.createContainerCmd (docker-client) "foobar")
      ;; (.createContainerCmd (docker-client)
      ;;                      MESOS_MASTER_IMAGE + ":" + MESOS_IMAGE_TAG)
      ;; .withName("minimesos-master-" + getClusterId() + "-" + getRandomId())
      ;; .withExposedPorts(new ExposedPort(MESOS_MASTER_PORT))
      ;; .withEnv(createMesosLocalEnvironment()

      )
    )
  )

(defn master-creator-function
  []
  (reify Function
    (apply [this zk]
      (prn "HEY YOOOOOO!!!!" zk)
      (mesos-master (docker-client) zk)
      ))
  )

(defn cluster-architecture
  []
  (-> (docker-client)
      ClusterArchitecture$Builder.
      .withZooKeeper
      (.withMaster (master-creator-function))
      (.withSlave "ports(*):[9200-9200,9300-9300]")
      .build)
  )

(defn new-cluster
  []
  (MesosCluster. (cluster-architecture))
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

