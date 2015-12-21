;; (ns satellite-slave.core-test
;;   (use satellite-slave.core
;;        clojure.test)
;;   (:require [clojure.java.io :as io]
;;             [cheshire.core :as json]
;; ;;            [com.containersol.minimesos.MesosCluster]
;;             ;; [com.containersol.minimesos :as mm]
;;             ))

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

