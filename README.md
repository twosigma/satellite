# Satellite

Satellite monitors, alerts on, and self-heals your Mesos cluster. 

# Architecture
## High level
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
## Little lower
`satellite-master` embeds Riemann and `satellite-slave` embeds a Riemann client.
`satellite-slave`s send one type of message to all the `satellite-masters`, a Riemann event that is the
result of a user-specified test.
