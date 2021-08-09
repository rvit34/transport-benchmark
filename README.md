**Objective**

Compare performance for different event-driven transport frameworks.

**Build and Run**

- run from root directory:\
  `./gradlew clean build`
- make changes in corresponding [env file](./.env) according to your needs
- establish transport (see below)
- launch _TransportLoadTest_:\
  `java -Xmx1G -Xms1G -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -DenvFile=./.env -jar ./build/libs/transport-bench-0.0.1-SNAPSHOT.jar`

**Transport**

_Kafka_\
Run Kafka & Zoo, see https://kafka.apache.org/documentation/#quickstart)

_Redpanda_ \
Read docs https://vectorized.io/docs \
For this benchmark only one system service is used.

_Aeron_\
Follow to instructions here \
https://aeroncookbook.com/aeron/media-driver/#c-media-driver \
https://github.com/real-logic/aeron/blob/master/aeron-driver/src/main/c/README.md \
After built run mediadriver using next command:
For benchmarks highly recommended to launch tuned media driver for low-latency, using next command (make sure you tuned snd/rcv buffers on OS Level):
```
./aeronmd \
-Daeron_print_configuration=true \
-Daeron_dir_delete_on_shutdown=true \
-Daeron_dir_warn_if_exists=true \
-Daeron_dir=/dev/shm/aeron-load-test \
-Daeron_threading_mode=DEDICATED \
-Daeron_sender_idle_strategy="noop" \
-Daeron_receiver_idle_strategy="noop" \
-Dconductor.idle.strategy="spin" \
-Daeron_term_buffer_sparse_file=false \
-Daeron_pre_touch_mapped_memory=true \
-Daeron_socket_so_sndbuf=2m \
-Daeron_socket_so_rcvbuf=2m \
-Daeron_rcv_initial_window_length=2m \
-Dargona_disable_bounds_checks=true \
-Daeron_mtu_length=8k
```
If you have issues with C-Media-Driver then you have ability to run it from java:
```
 java -cp aeron-all/build/libs/aeron-all-<version>.jar \
    -XX:+UnlockDiagnosticVMOptions \
    -XX:GuaranteedSafepointInterval=300000 \
    -XX:+UseBiasedLocking \
    -XX:BiasedLockingStartupDelay=0 \
    -XX:+UnlockExperimentalVMOptions \
    -XX:+UseZGC \
    -Djava.net.preferIPv4Stack=true \
    -Daeron.print.configuration=true \
    -Daeron.dir=/dev/shm/aeron-load-test \
    -Daeron.dir.warn.if.exists=true \
    -Daeron.dir.delete.on.shutdown=true \
    -Daeron.mtu.length=8k \
    -Daeron.socket.so_sndbuf=2m \
    -Daeron.socket.so_rcvbuf=2m \
    -Daeron.rcv.initial.window.length=2m \
    -Dagrona.disable.bounds.checks=true \
    -Daeron.term.buffer.sparse.file=false \
    -Daeron.pre.touch.mapped.memory=true \
    io.aeron.samples.LowLatencyMediaDriver
```
Read https://github.com/real-logic/Aeron/wiki/Performance-Testing for more details


**Benchmark**\
Very simple case: 1 Consumer, 1 Producer and 1 Broker. \
Producer sends proto-serialized messages (~78bytes each) and consumer receives them and make deserialization.\
See [TransportLoadTest.java](./src/main/java/org/bench/transports/TransportLoadTest.java) for more details.

**Env configuration**

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
kafka.producers.lingerMs=10
kafka.producers.batchSize=16384
kafka.producers.bufferMemory=33554432
kafka.consumers.idleStrategy=NO_IDLE
kafka.consumers.fetchMinBytes=1
kafka.consumers.fetchMaxWaitMs=500
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

**Benchmark results**

* all latencies in milliseconds
* latency include time spent on ser/de of proto messages

Results(KAFKA v2.8.0):

| RPS/Latency | 25th | 50th | 90th | 99th | 99.9th |
|---|---|---|---|---|---|
| 1K | 3.7 | 6.8 | 12.1 | 25 | 59.9 |
| 5K | 4.3 | 7.3 | 12.5 | 16.9 | 26.5 |
| 10K | 5.8 | 9.9 | 25.6 | 54 | 60.5 |
| 25K | 438 | 763 | 1372 | 1452 | 1456 |
| 50K | 911 | 2489 | 4073 | 4179 | 4198 |
| 100K | 1593 | 3169 | 4842 | 4969 | 4992 |

Results(Redpanda v21.7.6):

| RPS/Latency | 25th | 50th | 90th | 99th | 99.9th |
|---|---|---|---|---|---|
| 1K | - | - | - | - | - |
| 5K | 8 | 12.1 | 21.8 | 50.8 | 62 |
| 10K | 9.4 | 13.9 | 26.1 | 43 | 47 |
| 25K | 17.7 | 40 | 123.9 | 157.7 | 162.2 |
| 50K | 624.8 | 875 | 1000 | 1039 | 1043 |
| 100K | 819 | 1115 | 1357 | 1439 | 1450 |

Results(Aeron v1.34.0):

| RPS/Latency | 25th | 50th | 90th | 99th | 99.9th |
|---|---|---|---|---|---|
| 1K | 0.9 | 2 | 4.5 | 8 | 18.7 |
| 5K | 0.2 | 1 | 3.1 | 4.4 | 6 |
| 10K | 0.4 | 0.7 | 4.4 | 8.3 | 12.1 |
| 25K | 1 | 2.1 | 5.2 | 7.9 | 9.9 |
| 50K | 1.7 | 3.4 | 8.7 | 16.2 | 17.9 |
| 100K | 1.1 | 2.4 | 5.8 | 9 | 9.9 |

**Troubleshooting**

_Kafka Leader Not Available_\
If you see on test start up such logs like:
```
 Error while fetching metadata with correlation id 31 : {load-test-topic=LEADER_NOT_AVAILABLE}
```
Check that at least one kafka broker is started and just restart application with kafka client.

Sometimes kafka consumer in test just stuck from start and does not receive anything from kafka, in spite of producer sends messages successfully.
So the test stuck as well and awaiting result until timeout happens (10min timeout hardcoded).
Workaround for this is to run console consumer from kafka binaries:
```
./kafka-console-consumer.sh --topic load-test-topic --bootstrap-server localhost:9092
```
If it is able to consume messages, then cancel it and run test again.
