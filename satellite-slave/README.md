# Satellite Slave

An agent to monitor your Mesos Slaves.

## Requirements

Java (tested against Oracle JDK 1.7), Mesos, Zookeeper,
[Leiningen](http://leiningen.org/)

## Installation

Run `lein release-jar` to compile a jar in `./target`. Copy a copy of the
`standalone` jar to each Mesos slave host.

## Configuration

There is a sample master config file included in `/config/sample.clj`.  Since the master config file is a clojure program, you can run any sort of initialization code that you want here.  However, at minimum, you should define the "settings" var.

To make it easier to edit simple values, the sample master config loads data from a static json file in the same directory, `config/sample-settings.json`.  You can start off by just editing the values in this file.  Later on, if you need to write your own comets,
you can add those directly to the master config.clj file.

Thorough documentation of the final structure of the settings can be found at
`/src/satellite_slave/config.clj`.

To get started, all you should have to do is copy the sample files and list your
Satellite masters.

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
