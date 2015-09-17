# Satellite Slave

An agent to monitor your Mesos Slaves.

## Requirements

Java (tested against Oracle JDK 1.7), Mesos, Zookeeper,
[Leiningen](http://leiningen.org/)

## Installation

Run `lein release-jar` to compile a jar in `./target`. Copy a copy of the
`standalone` jar to each Mesos slave host.

## Configuration

There is a sample config included in `/config/sample.clj` and thorough
documentation of the config in `/src/satellite_slave/config.clj`.

All you should have to do is copy the sample file and list your Satellite 
masters.

## Running

Run each jar using `java` as usual, with the single argument being a path to
your config file. For example,

```
java -jar ./target/satellite.jar ./config/config.clj
```

## Security

Communication between Satellite Master and Slaves are insecure; secure
communication is unnecessary because the information from Slaves--host
metrics--is not considered confidential.

## Debugging

There isn't much information in the log file, but you can run the slave
with the --dry-run option. That will print all events to STDOUT. It will
not connect to the master.

```
java -jar ./target/satellite.jar ./config/config.clj --dry-run
```
