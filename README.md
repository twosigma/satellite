# Satellite

Satellite is a monitor, and sometimes more, for your Mesos cluster.

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
    across the Mesos masters.

3.  Satellite can provide a REST interface for accessing cached Mesos task
    metadata. To be very clear, Satellite does not cache the metadata, simply an
    interface to retrieve it, if it has been cached. This is useful if you have
    persisted task metadata to get around the weak persistence guarantees 
    currently offered by Mesos. This feature is optional.

## Requirements

Java (tested against Oracle JDK 1.7), Mesos, Zookeeper.

If you are using the cached metadata feature, Riak 1.4+ is also necessary.

## Installation

Run `lein release-jar` to compile a jar in `./target`. Copy a copy of the
`standalone` jar to each Mesos master host.

## Running

Run each jar using `java` as usual, with the single argument being a path to
your config file. For example,

```
java -jar satellite.jar config.clj
```

## Security

Communication between Satellite Master and Slaves are insecure; secure
communication is unnecessary because the information from Slaves--host
 metrics--is not considered confidential.

## REST API

### `GET /stats.json`

Return a JSONObject with  general statistics about the cluster.

Example:

```bash
curl satellite/stats.json
```

Response:

```json
{
 "test-key": "val1",
 "second-key": "val2"
}
```

### `GET /whitelist/{flag}`

`flag` can take three values: `on`, `off`, and `all`.

Return the current inventory as a single sorted text file. If `on`, return only
hosts in the whitelist; if `off`, return only hosts in inventory, but not in
the whitelist; and if `all`, return all hosts in the inventory.

Examples:

Request:

```bash
curl example.com/whitelist/on
```

Response:

```
my.dope.host
```

Request:

```
curl example.com/whitelist/off
```

Response:

```
my.broken.host
```

Request:

```
curl example.com/whitelist/all
```

Response:

```
my.broken.host
my.dope.host
```

### `GET /whitelist/host/{host}`

Get the status of the `host`. If `host` is not in the inventory, return `404`.
Else return `200` and the appropriate status: if `host` is currently in the
whitelist, the body of the response will be "on," otherwise it will be "off."

Example:

Request:

```bash
curl example.com/whitelist/host/my.dope.host
```

Response:

```
On
```

### `DELETE /whitelist/host/{host}`

Remove `host` from the inventory. Returns `204` on success. Returns `404` if
`host` was not in the inventory.

Example:

Request:

```bash
curl -X DELETE example.com/whitelist/host/my.dope.host
```

Response:

```
```

### `PUT /whitelist/host/{host}/{flag}`

Set `host` to `flag`. If `host` is not currently in the inventory, `host` will
be added. If `flag` is `on`, `host` will be added to the whitelist of each Mesos
Master after passing validation.

For `host` to pass validation it must be

* DNS resolvable from the Satellite host or be a valid IPv4/6 address
* pingable from the Satellite host
* and pass a user-specified predicate, which by default is always true.

Success will return status code `201` and failure will return status code `400`.

```bash
curl -X PUT example.com/whitelist/host/my.dope.host/off
```

Response:

```
my.dope.host is now off.
```

### `GET /state.json`

This endpoint will only function if Satellite is run with a config containing
Riak information.

Requires `task` query parameter, which is a Mesos task UUID. e.g.

```
curl satellite/state.json?task=48db4b52-7697-4776-b463-b28e7746b477
```

## Operational Hints

When a host changes state (from off to on, vice-versa, or when added to the
inventory), a line similar to

```
INFO [2015-01-22 02:18:21,003] riemann task 0 - satellite.whitelist - Turning my.dope.host on -> off
```

will be logged.

If you are using the `ensure-all-tests-on-whitelisted-host-pass` recipe, note
that you will continue to get log messages like

```
WARN [2015-01-22 02:26:58,000] riemann task 1 - satellite.recipes - Turning off host my.dope.host due to failed test: #riemann.codec.Event{:host my.dope.host, :service mesos/slave/free swap in MB, :state critical, :description nil, :metric 1, :tags nil, :time 1421893578, :ttl 300.0}
```

even after the host is already off.

## Slave events

Rather than call-and-response of the Mesos slaves, Satellite runs a Riemann TCP
server to receive events from the Satellite Slaves. Even though each Satellite
receives the Satellite Slave event stream, only the Satellite Master tracking
the leader Mesos master will put it into its own stream.

# Nomenclature

http://en.wikipedia.org/wiki/Six_Finger_Satellite
