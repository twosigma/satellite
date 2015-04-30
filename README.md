# Satellite

Satellite monitors, alerts on, and self-heals your Mesos cluster. 

## Overview

Satellite currently serves three functions, each adding functionality to Mesos:

1.  Most importantly, Satellite Master directly monitors Mesos masters and
    receives monitoring information from Mesos slaves through Satellite
    Slaves, producing a Riemann (http://riemann.io/index.html) event stream for
    aggregate statistics of the cluster (e.g., utilization, number of tasks
    lost) as well as events specific to the masters (e.g., how many leaders are
    there).

    As Satellite embeds Riemann, you can do anything you would usually do with a
    Riemann event stream. You can configure to alert (e.g., email and pagerduty),
    feed your dashboards, and trigger whitelist updates. If you already have a
    primary Riemann server, you can forward events to that and contain all or
    some of the stream eventing logic there; the power is yours. You can look at
    our recipes in `src/recipes.clj` for patterns we have found useful.

2.  Satellite provides a REST interface for interacting with the Mesos master
    whitelist. The whitelist is a text file of hosts to which the master will
    consider sending tasks. Satellite ensures that update requests are consistent
    across the Mesos masters. See `Whitelist` below for a more detailed
    explanation of the model for interacting with the whitelist.

3.  Satellite can provide a REST interface for accessing cached Mesos task
    metadata. To be very clear, Satellite does not cache the metadata, simply an
    interface to retrieve it, if it has been cached. This is useful if you have
    persisted task metadata to get around the weak persistence guarantees
    currently offered by Mesos. This feature is optional.

### Whitelist

As we said above, the Mesos whitelist is a text file of hosts to which the
master will consider sending tasks. Satellite adds two additional conceptual
whitelists to the mix:

1.  A managed whitelist: Hosts that have been entered automatically in response
    to your Riemann configuration.
2.  A manual whitelist: If a host is in this whitelist, its status overrides
    that in the managed whitelist. This is to allow manual override. The
    interface to this whitelist is in the PUT and DELETE REST endpoints.

A periodic merge operation merges these two to the whitelist file that Mesos
observes.

## Architecture
### High level
There are two kinds of Satellite processes: `satellite-master`s and `satellite-slave`s.
For each `mesos-master` and `mesos-slave` process, there is a `satellite-master` and `satellite-slave`
process watching it respectively.
<pre>

| Follower         | Leader           | Follower         |
|------------------|------------------|------------------|
| mesos-master     | mesos-master     | mesos-master     |
| satellite-master | satellite-master | satellite-master |

      ^               <-  ^  ->                 ^
      |  -> ->          \ | /           <-  <-  |
      | / _/             \|/              \,_ \ |

| satellite-slave | satellite-slave | satellite-slave |
| mesos-slave     | mesos-slave     | mesos-slave     |
</pre>
### Little lower
`satellite-master` embeds Riemann and `satellite-slave` embeds a Riemann client.
`satellite-slave`s send one type of message to all the `satellite-masters`, a Riemann event that is the
result of a user-specified test.
