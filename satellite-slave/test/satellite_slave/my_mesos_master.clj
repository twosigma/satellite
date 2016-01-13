(ns satellite-slave.my-mesos-master
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
           )

  (:gen-class
   :extends com.containersol.minimesos.mesos.MesosMaster ))



