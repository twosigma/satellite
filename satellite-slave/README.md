# Satellite Slave

An agent to monitor your Mesos Slaves.

## Requirements

Java (tested against Oracle JDK 1.7), Mesos, Zookeeper.

## Installation

Run `lein release-jar` to compile a jar in `./target`. Copy a copy of the
`standalone` jar to each Mesos slave host.

## Running

Run each jar using `java` as usual, with the single argument being a path to
your config file. For example,

```
java -jar satellite.jar config.clj
```

There is a sample config included in `/config/sample.clj` and thorough
documentation of the config in `/src/satellite_slave/config.clj`.

## Security

Communication between Satellite Master and Slaves are insecure; secure
communication is unnecessary because the information from Slaves--host
metrics--is not considered confidential.
