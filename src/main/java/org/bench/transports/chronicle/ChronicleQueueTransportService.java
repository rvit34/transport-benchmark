package org.bench.transports.chronicle;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.VanillaBytes;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.bench.transports.TransportService;
import org.bench.transports.chronicle.ex.ChronicleQueuePublishException;
import org.bench.transports.utils.CommonUtil;
import org.bench.transports.utils.EnvVars;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Slf4j
public class ChronicleQueueTransportService implements TransportService {

    private static final String QUEUE_DIR = EnvVars.getValue("chronicle.queueDir", "./build/cq");
    private final String consumerIdleStrategy = EnvVars.getValue("chronicle.consumerIdleStrategy","NoOp");

    // key - topicName, value - queue with files
    private final Map<String, ChronicleQueue> queues;

    private final ThreadLocal<VanillaBytes> buffer = ThreadLocal.withInitial(Bytes::allocateElasticDirect);
    private final ExecutorService consumerPollingService = Executors.newSingleThreadExecutor();

    public ChronicleQueueTransportService() {
        queues = Map.of(
                TransportService.LOAD_TEST_TOPIC,
                SingleChronicleQueueBuilder.single(QUEUE_DIR.concat("/" + TransportService.LOAD_TEST_TOPIC)).build()
        );
    }

    @Override
    public CompletableFuture<Void> send(MessageLite request, String destination) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        final var queue = queues.get(destination);
        if (queue == null) {
            result.completeExceptionally(new ChronicleQueuePublishException(String.format("no queue created for topic %s", destination)));
            return result;
        }
        var bytes = buffer.get().clear();
        bytes.write(request.toByteArray());
        final var appender = queue.acquireAppender();
        try {
            appender.writeBytes(bytes);
            if (log.isDebugEnabled()) {
                log.debug("queue index after write: {}", appender.lastIndexAppended());
            }
            result.complete(null);
        } catch (Exception ex) {
            result.completeExceptionally(ex);
        }
        return result;
    }

    @Override
    public void subscribeOnEvents(Consumer<MessageLite> onEventConsumer, Parser<? extends MessageLite> parser, String source) {
        final var queue = queues.get(source);
        if (queue == null) {
            throw new IllegalStateException(String.format("no queue created for topic %s", source));
        }
        consumerPollingService.submit(() -> {
            final var tailer = queue.createTailer();
            boolean isSomethingRead;
            while (!Thread.interrupted()) {
                var bytes = buffer.get().clear();
                isSomethingRead = tailer.readBytes(bytes);
                if (isSomethingRead) {
                    try {
                        MessageLite event = parser.parseFrom(bytes.toByteArray());
                        onEventConsumer.accept(event);
                    } catch (InvalidProtocolBufferException ex) {
                        log.error("unable to parse proto event from bytes", ex);
                    }
                }
                if (!"NoOp".equals(consumerIdleStrategy)) {
                    Thread.onSpinWait();
                }
            }
        });
    }

    @Override
    public void shutdown(){
        log.info("Shutting down transport...");
        for (ChronicleQueue queue : queues.values()) {
            CommonUtil.closeQuietly(queue);
        }
        try {
            CommonUtil.removeDirectoryRecursively(QUEUE_DIR);
        } catch (IOException ex) {
            log.error("unable to clean up folder {}", QUEUE_DIR, ex);
        }
        log.info("Transport has been shut down successfully");
    }
}
