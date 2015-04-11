(streams
 (where (service #"mesos/.*")
        prn)
 (where (service #"mesos/slave.*")
        (ensure-all-tests-on-whitelisted-host-pass)))
