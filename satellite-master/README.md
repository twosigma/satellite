# satellite-master

## Requirements

Java (tested against Oracle JDK 1.7), Mesos, Zookeeper,
[Leiningen](http://leiningen.org/)

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

Please rely on the status code and not the response body text. The latter is
meant for humans and is very subject to change! If you believe either a status
code or body could be improved, issues and pull requests are very welcome.

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

### * `/whitelist`

The `GET` endpoints expose the view that Mesos sees. The `PUT` and `DELETE`
endpoints modify the manual whitelist.

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

Remove `host` from the manual whitelist. Returns `200` on success. Returns `404` if
`host` was not in the inventory.

Example:

Request:

```bash
curl -X DELETE example.com/whitelist/host/my.dope.host
```

Response:

```
my.dope.host was removed from the whitelist.
```

### `PUT /whitelist/host/{host}/{flag}`

Override `host` to `flag`. If `host` is not currently in the manual whitelist,
`host` will be added. If `flag` is `on`, `host` will be added to the whitelist
of each Mesos Master after passing validation.

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
