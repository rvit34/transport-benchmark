**Objective**

Compare performance for different event-driven transport frameworks.

**Build and Run**

- run from root directory:\
  `./gradlew clean build`
- make changes in corresponding [env file](./.env) according to your needs
- run transport (Kafka&Zoo, see https://kafka.apache.org/documentation/#quickstart)  
- launch _TransportLoadTest_:\
  `java -Xmx1G -Xms1G -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -DenvFile=./.env -jar ./build/libs/transport-bench-0.0.1-SNAPSHOT.jar`

**Benchmark**\
Very simple case: 1 Consumer, 1 Producer and 1 Broker. \
Producer sends proto-serialized messages (~78bytes each) and consumer receives them and make deserialization.\
See [TransportLoadTest.java](./src/main/java/org/bench/transports/TransportLoadTest.java) for more details.

**Env configuration and results**

Load test along with Kafka&Zoo were run on the same host metal machine.
Machine configuration:
```
Ubuntu 18.04.5 LTS x86_64 4.15.0-153-generic
Intel(R) Core(TM) i7-4770 CPU @ 3.40GHz (8 logical cores)
16GB RAM (swap is off)
Samsung SSD 850 Evo 512GB (btrfs)
```

Before start benchmarking I made some TCP tuning:
```
sudo su
echo 1 > /proc/sys/net/ipv4/tcp_low_latency
sysctl net.core.rmem_max=2097152
sysctl net.core.wmem_max=2097152
```

Java version:
```
openjdk version "11.0.5-BellSoft" 2019-10-15
OpenJDK Runtime Environment (build 11.0.5-BellSoft+11)
OpenJDK 64-Bit Server VM (build 11.0.5-BellSoft+11, mixed mode)
```
Kafka&Zoo version:
```
kafka_2.13-2.8.0
zookeeper v3.5.9
```
I run Kafka as one broker with 2G RAM and default settings. Command line arguments were also default:
```
java -server -Xmx2G -Xms2G -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:InitiatingHeapOccupancyPercent=35 -XX:+ExplicitGCInvokesConcurrent -XX:MaxInlineLevel=15 -Djava.awt.headless=true ...
```
Topic configurations:
```
kafka.topic.partitions=1
kafka.topic.replicationFactor=1
```
Producer/Consumer configurations (no batching):
```
kafka.producers.acks=all
kafka.producers.lingerMs=0
kafka.producers.batchSize=16384
kafka.producers.bufferMemory=33554432
kafka.consumers.idleStrategy=NO_IDLE
kafka.consumers.fetchMinBytes=1
kafka.consumers.fetchMaxWaitMs=0
kafka.consumers.maxPollIntervalMs=10000
kafka.consumers.maxPollRecords=1
kafka.consumers.enableAutoCommit=false
kafka.consumers.autoOffsetReset=latest
kafka.consumers.groupId=load-generator
kafka.consumers.sessionTimeoutMs=120000
kafka.consumers.heartbeatIntervalMs=40000
```

Test config:
```
warmUpIterations=10K
iterations=100K
```

Results:

| RPS/Latency | 25th | 50th | 90th | 99th | 99.9th |
|---|---|---|---|---|---|
| 1K | 2.2 | 4.6 | 13.7 | 31.2 | 41.5 |
| 5K | 3.3 | 6.6 | 26.9 | 46.7 | 51.6 |
| 10K | 3.8 | 7.6 | 22.5 | 30.3 | 33.2 |
| 25K | 247.2 | 598.2 | 1428 | 1459 | 1461 |
| 50K | 1870 | 2594 | 43945 | 43983 | 43988 |
| 100K | 3640 | 4326 | 39069 | 39484 | 39535 |
* all latencies in milliseconds
* latency include time spent on ser/de of proto messages
