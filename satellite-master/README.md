# satellite-master

## Requirements

Java (tested against Oracle JDK 1.7), Mesos, Zookeeper,
[Leiningen](http://leiningen.org/)

If you are using the cached metadata feature, Riak 1.4+ is also necessary.

## Installation

Run `lein release-jar` to compile a jar in `./target`. Copy a copy of the
`standalone` jar to each Mesos master host.

## Configuration

It is recommended to replace all occurances of localhost with the
server's host name that the Satellite master is installed on assuming
you'd like to connect to things like the API outside of localhost.

Make sure local-whitelist-path is pointing to your Mesos master
whitelist. Default is /etc/mesos/whitelist.

Update riemann-tcp-server-options to the server's name or IP. localhost
is the default setting but that will not allow other servers to connect
to the leader via Riemann.

Satellite-master accepts any number of config files, specified as command line
parameters.  Settings from the config files will be combined into a single runtime
configuration, which is logged when Satellite starts up.  If more than one config file
contains a value for the same config parameter, the value in rightmost file (by
position in command line invocation) wins.

Config files may be in either .clj or JSON format.  See the examples in config/.


## Running

Run each jar using `java` as usual, with the single argument being a path to
your config file. For example,

```
java -jar ./target/satellite.jar ./config/satellite-config.clj
```

## Security

Communication between Satellite Master and Slaves are insecure; secure
communication is unnecessary because the information from Slaves--host
 metrics--is not considered confidential.

## REST API

Satellite's REST API can be accessed on port 5001. You can change the port
using the config parameter service-port.

Please rely on the status code and not the response body text. The latter is
meant for humans and is very subject to change! If you believe either a status
code or body could be improved, issues and pull requests are very welcome.

### `GET /metrics/snapshot`

Return a JSONObject with general statistics about the cluster; the
output type will be {String : Number}. Note that these metrics are
different than those provided by the Mesos /metrics/snapshot
endpoint. The intention of this Satellite endpoint is to mirror the
Mesos API, hinting that it provides point-in-time metrics about the
state of the cluster. Eventually we will allow users to configure what
metrics are exposed here through the Riemann config.

Example:

```bash
curl http://satellite1.example.com:5001/metrics/snapshot
```

Response:

```json
{
 "test-key": val1,
 "second-key": val2
}
```

### * `/whitelist`

The `GET` endpoints expose the view that Mesos sees. The `PUT` and `DELETE`
endpoints modify the manual whitelist.

### `GET /whitelist/{flag}`

`flag` can take three values: `on`, `off`, and `all`.

Return the current state of hosts. If `on`, return only
hosts in the whitelist; if `off`, return only hosts in inventory, but not in
the whitelist; and if `all`, return all hosts in the inventory.

Examples:

Request:

```bash
curl http://satellite1.example.com:5001/whitelist/all
```

Response:

```json
{
 host1: {
     managed-events: {
         mytest: {
             host: "host1",
             service: "mytest",
             state: "critical",
             description: "mydescription",
             mertic: 0,
             tags: null,
             time: 1435073979,
             ttl: 300
             }
     },
     manual-events: {
        mymanualoverride: {
            state: "ok"
            description: "manually override host flag to on"
            time: 1435073979,
            ttl: 172800
        }
     },
     managed-flag: "off",
     manual-flag: "on"
 }
}
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

```json
{
    managed-events: {
         mytest: {
             host: "host1",
             service: "mytest",
             state: "critical",
             description: "mydescription",
             mertic: 0,
             tags: null,
             time: 1435073979,
             ttl: 300
             }
     },
     manual-events: {
        mymanualoverride: {
            state: "ok"
            description: "manually override host flag to on"
            time: 1435073979,
            ttl: 172800
        }
     },
     managed-flag: "off",
     manual-flag: "on"
}
```

### `DELETE /whitelist/host/{host}`

Remove `host` from the inventory. Returns `200` on success. Returns `404` if
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

### `POST /whitelist/host/{host}/event/{event}`

Override manual 'event' on 'host', If `host` is not currently in the manual whitelist,
`host` will be added. Manual flag is decided by state of most recent manual event.

For `host` to pass validation it must be

* DNS resolvable from the Satellite host or be a valid IPv4/6 address
* pingable from the Satellite host
* and pass a user-specified predicate, which by default is always true.

`ttl` is a string describing the time to live of this event, i.e., `15minute`, `24hour`, `7day`, `2week`. Event expires when current time is after event creation time plus `ttl` and will be garbage collected.

`state` is either `ok` or `critical`. `ok` will turn on the host, `critical` will turn off the host

Success will return status code `201` and failure will return status code `400`.

Example:

Request:

```bash
curl -H "Content-Type: application/json" -X POST -d '{"ttl": "7day" "state": "critical" "description": "reconfiguraton"}' example.com:5001/whitelist/host/my.dope.host/event/manual
```

Response:

```bash
my.dope.host updated
```

### `DELETE /whitelist/host/{host}/event/{event}`

Delete manual 'event' on 'host'. This will trigger revaluation of manual flag.

```bash
curl -X DELETE example.com:5001/whitelist/host/my.dope.host/event/manual
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

## Debugging

The easiest way to make sure the Satellite leader is receiving events,
is to change the riemann-config.clj file to just:

```
(streams prn)
```

This will print all events to STDOUT.

To make sure the leader can receive events from clients, send an event
with your favorite Riemann client either from the current leader or from
one of the servers in your cluster.

```
$ cd ~/satellite/satellite-slave
~/satellite/satellite-slave $ lein repl
satellite-slave.core=> (require '[riemann.client :as r])
#'satellite-slave.core/r
satellite-slave.core=> (def c (r/tcp-client {:host "satellite1.example.com" :port 5555}))
#'satellite-slave.core/c
satellite-slave.core=> (-> c (r/send-event {:service "foo" :state "ok"})
                  #_=> (deref 5000 ::timeout))
#riemann.codec.Msg{:ok true, :error nil, :events [], :query nil, :decode-time 10273872699458978}
```

If you cannot connect using the host name, you'll see an error message
like this:

```
IOException no channels available com.aphyr.riemann.client.TcpTransport.sendMessage (TcpTransport.java:293)

```

# Nomenclature

http://en.wikipedia.org/wiki/Six_Finger_Satellite
