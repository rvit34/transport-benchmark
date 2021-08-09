package org.bench.transports;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface TransportService {

    String LOAD_TEST_TOPIC = "load-test-topic";

    CompletableFuture<Void> send(MessageLite request, String destination);

    /**
     * @param onEventConsumer - will be called always in the same one thread (transport polling thread)
     */
    void subscribeOnEvents(Consumer<MessageLite> onEventConsumer, Parser<? extends MessageLite> parser, String source);
    void shutdown();
}
