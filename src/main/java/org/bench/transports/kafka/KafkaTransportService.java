package org.bench.transports.kafka;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.bench.transports.TransportService;
import org.bench.transports.utils.CommonUtil;
import org.bench.transports.utils.EnvVars;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.bench.transports.utils.CommonUtil.tryParse;

@Slf4j
public class KafkaTransportService implements TransportService {

    private final KafkaConsumer<byte[], byte[]> consumer;
    private final KafkaProducer<byte[], byte[]> producer;

    private final ThreadLocal<ByteArrayOutputStream> outputStreamTL = ThreadLocal.withInitial(ByteArrayOutputStream::new);
    private final ExecutorService consumerPollingService = Executors.newSingleThreadExecutor();

    private volatile Consumer<MessageLite> eventConsumer;
    private volatile Parser<? extends MessageLite> eventParser;

    public KafkaTransportService(KafkaConsumer<byte[], byte[]> consumer, KafkaProducer<byte[], byte[]> producer) {
        this.consumer = consumer;
        this.producer = producer;
    }

    @Override
    public CompletableFuture<Void> send(MessageLite request, String topic) {
        final var outputStream = outputStreamTL.get();
        outputStream.reset();
        final var payload = CommonUtil.toByteArrayDelimited(outputStream, request);
        final var record = new ProducerRecord<byte[], byte[]>(topic, payload); // todo: reuse it somehow
        final var result = new CompletableFuture<Void>();
        if (log.isDebugEnabled()) {
            log.debug("send timestamp(nanos):{}", System.nanoTime());
        }
        producer.send(record, ((metadata, exception) -> {
            if (exception != null) {
                log.error("unable to send request to kafka", exception);
                result.completeExceptionally(exception);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("record was sent to kafka");
                }
                result.complete(null);
            }
        }));
        return result;
    }

    /**
     * for simplicity purposes only one consumer might be registered at time
     */
    @Override
    public void subscribeOnEvents(Consumer<MessageLite> onEventConsumer, Parser<? extends MessageLite> eventParser, String topic) {
        if (eventConsumer != null) {
            throw new IllegalStateException("only one consumer might be registered at time");
        }
        this.eventConsumer = onEventConsumer;
        this.eventParser = eventParser;
        consumerPollingService.submit(() -> pollingRecords(topic));
    }

    @Override
    public void shutdown() {
        if (!consumerPollingService.isShutdown()) {
            consumerPollingService.shutdown();
            try {
                consumerPollingService.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("interruption occurred while awaiting termination");
            }
            if (!consumerPollingService.isShutdown()) {
                consumerPollingService.shutdownNow();
            }
        }
        if (producer != null) {
            producer.close(Duration.of(3, ChronoUnit.SECONDS));
        }
    }

    private void pollingRecords(String topic) {
        consumer.subscribe(Collections.singletonList(topic));
        log.info("Consumer subscribed on events from topic {}", topic);
        final var idleStrategy = EnvVars.getValue("kafka.consumers.idleStrategy", "NO_IDLE");
        final var noWait = Duration.of(0, ChronoUnit.SECONDS);
        long ts = 0;
        while (!Thread.interrupted()) {
            final var records = consumer.poll(noWait);
            if (records.isEmpty()) {
                if (idleStrategy.equals("NO_IDLE")) {
                    continue;
                } else {
                    Thread.onSpinWait();
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("received {} records. Timestamp(nanos): {}", records.count(), System.nanoTime());
                ts = System.nanoTime();
            }

            records.forEach(record -> {
                if (log.isDebugEnabled()) {
                    log.debug("Consumer Record. Partition:{}, Offset:{})",
                            record.partition(), record.offset()
                    );
                }
                MessageLite event = tryParse(record.value(), eventParser);
                if (log.isDebugEnabled()) {
                    log.debug("message parsed");
                }
                if (event != null && eventConsumer != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("pass it on eventConsumer");
                    }
                    eventConsumer.accept(event);
                }
            });

            if (log.isDebugEnabled()) {
                log.debug("records processed");
                final long processingDuration = System.nanoTime() - ts;
                log.debug("duration of processing {} records(nanos):{}", records.count(), processingDuration);
            }

            consumer.commitAsync((offsets, exception) -> {
                if (exception != null) {
                    log.error("failed to commit offsets", exception);
                }
            });
        }
        consumer.close(Duration.of(3, ChronoUnit.SECONDS));
    }
}
