package org.bench.transports;

import com.google.protobuf.MessageLite;
import it.unimi.dsi.fastutil.doubles.DoubleOpenHashSet;
import it.unimi.dsi.fastutil.doubles.DoubleSet;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.bench.transports.proto.OrderProto;
import org.bench.transports.utils.EnvVars;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


import static org.apache.commons.math3.util.Precision.round;
import static org.bench.transports.proto.OrderProto.OrderSide.ORDER_SIDE_BUY;
import static org.bench.transports.proto.OrderProto.OrderSide.ORDER_SIDE_SELL;

@Slf4j
public class TransportLoadTest {

    private DoubleSet latencies;
    private TransportService transportService;
    private volatile boolean isWarmingUp = false;
    private final String topicName = EnvVars.getValue("kafka.topic.name", TransportService.LOAD_TEST_TOPIC);

    private final Random random = new Random();
    private final Percentile percentile = new Percentile();
    private ExecutorService executors;

    private final ThreadLocal<OrderProto.PlaceOrderRequest.Builder> placeOrderRequestBuilderTL = ThreadLocal.withInitial(OrderProto.PlaceOrderRequest::newBuilder);
    private final ThreadLocal<OrderProto.Timings.Builder> timingsBuilderTL = ThreadLocal.withInitial(OrderProto.Timings::newBuilder);

    private volatile double approxRps;
    private volatile CountDownLatch endLatch;

    void runTest() {
        final var transport = EnvVars.getValue("application.orderTransport", "kafka");
        if ("kafka".equals(transport) && !createTopic()) {
            log.warn("unable to create a topic. Probably Kafka will create it by itself");
        }

        final var iterations = Integer.parseInt(EnvVars.getValue("transportLoadTest.iterations", "1000"));
        final var warmUpIterations = Integer.parseInt(EnvVars.getValue("transportLoadTest.warmUpIterations", "1000"));
        latencies = new DoubleOpenHashSet(iterations);
        transportService = TransportServiceFactory.buildTransportService();

        int parallelism = Integer.parseInt(EnvVars.getValue("transportLoadTest.sendThreadsCount", "0"));
        if (parallelism == 0) {
            parallelism = Runtime.getRuntime().availableProcessors();
        }
        executors = Executors.newFixedThreadPool(parallelism);

        startConsumingRequests();

        if (warmUpIterations > 0) {
            isWarmingUp = true;
            log.info("warming up({} iterations)...", warmUpIterations);
            run(warmUpIterations);
            log.info("warming up completed");
            isWarmingUp = false;
        }

        log.info("loading(iterations({}))...", iterations);
        final long ts = System.nanoTime();
        run(iterations);
        final long testDurationSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - ts);
        log.info("loading done");

        printStats(testDurationSeconds);

        log.info("shutdown workers...");
        if (executors != null) {
            executors.shutdownNow();
        }
        System.exit(0);
    }

    private void startConsumingRequests() {
        transportService.subscribeOnEvents(messageLiteConsumer, OrderProto.PlaceOrderRequest.parser(), topicName);
    }

    private final Consumer<MessageLite> messageLiteConsumer = messageLite -> {
        if (!isWarmingUp && messageLite instanceof OrderProto.PlaceOrderRequest) {
            final var tradingRequest = (OrderProto.PlaceOrderRequest) messageLite;
            final var sentTimestamp = tradingRequest.getTimings().getRequestSentTimestamp();
            final long latencyNanos = System.nanoTime() - sentTimestamp;
            if (log.isDebugEnabled()) {
                log.debug("latency(nanos):{}", latencyNanos);
            }
            latencies.add(TimeUnit.NANOSECONDS.toMicros(latencyNanos));
        }
        endLatch.countDown();
    };

    private void run(int iterations) {
        final int initialIterations = iterations;
        final var requestsLeftCounter = new AtomicInteger(iterations);
        final var maxRpsLimit = Double.parseDouble(EnvVars.getValue("transportLoadTest.maxRpsLimit","100000"));
        final var startLatch = new CountDownLatch(1);
        endLatch = new CountDownLatch(iterations);
        final long startIterationsTimestamp = System.nanoTime();

        while (iterations > 0) {
            executors.submit(() -> {
                try {
                    startLatch.await();
                    transportService.send(buildRequest(), topicName);
                } catch (InterruptedException ex) {
                    log.error("request interrupted. Details: {}", ex.getMessage());
                }

                final var requestsLeft = requestsLeftCounter.decrementAndGet();
                final var requestsSent = initialIterations - requestsLeft;
                double approxRpsValue = calcApproxRps(requestsSent, startIterationsTimestamp);
                if (requestsLeft == 0) {
                    approxRps = approxRpsValue;
                } else {
                    while (approxRpsValue > maxRpsLimit) {
                        // we too fast, hold on a bit
                        Thread.onSpinWait();
                        approxRpsValue = calcApproxRps(requestsSent, startIterationsTimestamp);
                    }
                }
                });
            iterations--;
        }
        startLatch.countDown();
        log.info("awaiting till all requests will be completed...");
        try {
            endLatch.await(10, TimeUnit.MINUTES);
        } catch (InterruptedException ex) {
            log.error("awaiting was interrupted. Details: {}", ex.getMessage());
        }
    }

    private double calcApproxRps(int iterations, long startIterationsTimestamp) {
        long allRequestsSentDuration = System.nanoTime() - startIterationsTimestamp;
        long millisSpent = TimeUnit.NANOSECONDS.toMillis(allRequestsSentDuration);
        return (((double) iterations) / millisSpent) * 1000.0;
    }

    private OrderProto.PlaceOrderRequest buildRequest() {
        final long ts = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("building request timestamp(nanos):{}", ts);
        }
        final var timings = timingsBuilderTL.get().clear()
                .setRequestSentTimestamp(ts)
                .build();
        return placeOrderRequestBuilderTL.get().clear()
                .setQuantity(String.valueOf(1 + random.nextDouble() * 999))
                .setPrice(String.valueOf(1 + random.nextDouble() * 999))
                .setSide(random.nextBoolean() ? ORDER_SIDE_BUY : ORDER_SIDE_SELL)
                .setType(OrderProto.OrderType.ORDER_TYPE_LIMIT)
                .setTrader("stress-test-kafka-trader")
                .setTimings(timings)
                .build();
    }

    private boolean createTopic() {
        final var config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, EnvVars.getValue("kafka.bootstrapServers", "localhost:9092"));

        try(final var adminClient = Admin.create(config)) {
            final int partitions = Integer.parseInt(EnvVars.getValue("kafka.topic.partitions", "1"));
            final short replicationFactor = Short.parseShort(EnvVars.getValue("kafka.topic.replicationFactor", "1"));
            final String retentionMs = EnvVars.getValue("kafka.topic.retentionMs", "600000");
            try {
                for (String topic : adminClient.listTopics().names().get()) {
                    if (topic.equals(topicName)) {
                        log.debug("topic {} already exist", topicName);
                        return true;
                    }
                }
            } catch (Exception ex) {
                log.error("unable to list topics in kafka. Details: {}", ex.getMessage());
                return false;
            }
            final var newTopic = new NewTopic(topicName, partitions, replicationFactor);
            newTopic.configs(Map.of("retention.ms", retentionMs));
            final var result = adminClient.createTopics(Collections.singletonList(newTopic));
            try {
                log.info("creating a new topic {} ...", topicName);
                result.all().get();
                log.info("topic has been created successfully");
            } catch (Exception ex) {
                log.error("unable to create a new topic {}. Details: {}", topicName, ex.getMessage());
                return false;
            }
        }
        return true;
    }

    private void printStats(long testDurationSeconds) {
        log.info("Test time spent(seconds): {}", testDurationSeconds);
        log.info("Approximate rps: {}", round(approxRps, 2));
        printLatencyInPercentiles("latency(micros)", latencies);
    }

    private void printLatencyInPercentiles(String details, DoubleSet data) {
        log.info(details);
        percentile.setData(data.toDoubleArray());
        log.info("25th: {}", round(percentile.evaluate(25.0), 2));
        log.info("50th: {}", round(percentile.evaluate(50.0), 2));
        log.info("90th: {}", round(percentile.evaluate(90.0), 2));
        log.info("99th: {}", round(percentile.evaluate(99.0), 2));
        log.info("99.9th: {}", round(percentile.evaluate(99.9), 2));
    }

    public static void main(String[] args) {
        new TransportLoadTest().runTest();
    }
}
