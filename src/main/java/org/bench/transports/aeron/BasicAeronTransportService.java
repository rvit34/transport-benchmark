package org.bench.transports.aeron;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.UnsafeBuffer;
import org.bench.transports.TransportService;
import org.bench.transports.aeron.ex.AeronPublishRequestException;
import org.bench.transports.aeron.ex.AeronSubscribeException;
import org.bench.transports.utils.CommonUtil;
import org.bench.transports.utils.EnvVars;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.aeron.Publication.*;

@Slf4j
public class BasicAeronTransportService implements TransportService {
    @Nullable
    protected final MediaDriver mediaDriver;
    protected final Aeron aeronClient;

    protected static final int FRAGMENTS_LIMIT = 1;
    private static final int PUBLICATION_OR_SUBSCRIPTION_CONNECTED_MAX_WAIT_MS = 30000;

    protected final String channel = EnvVars.getValue("aeron.channel", "aeron:ipc");

    protected final Object2IntArrayMap<String> topicsToStreamIdMap = new Object2IntArrayMap<>(2);
    protected final Map<String, Publication> registeredPublications = new ConcurrentHashMap<>();
    protected final Map<String, Subscription> registeredSubscriptions = new ConcurrentHashMap<>();

    protected final ThreadLocal<UnsafeBuffer> bufferTL = ThreadLocal.withInitial(UnsafeBuffer::new);
    protected final AtomicBoolean subscriberIsPolling = new AtomicBoolean();

    protected final ExecutorService subscriberPollingService = Executors.newSingleThreadExecutor();

   protected final AeronFactory aeronFactory = new AeronFactory();

    BasicAeronTransportService() {
        initStreamIds();
        this.mediaDriver = aeronFactory.createAndLaunchMediaDriver();
        this.aeronClient = aeronFactory.createAndConnectAeronClient(mediaDriver);
    }

    private void initStreamIds() {
        topicsToStreamIdMap.put(TransportService.LOAD_TEST_TOPIC, 1);
    }

    @Override
    public CompletableFuture<Void> send(MessageLite request, String destination) {
        final var publication = getPublication(destination);
        final var future = new CompletableFuture<Void>();
        if (publication == null) {
            future.completeExceptionally(new AeronPublishRequestException("unable to register publication"));
            return future;
        }
        final long startTs = System.nanoTime();
        long timeSpentMs;
        while (!publication.isConnected()) {
            timeSpentMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTs);
            if (timeSpentMs >= PUBLICATION_OR_SUBSCRIPTION_CONNECTED_MAX_WAIT_MS) {
                future.completeExceptionally(new AeronPublishRequestException("publication connection awaiting timed out"));
                return future;
            }
            Thread.onSpinWait();
        }
        final var buffer = bufferTL.get();
        buffer.wrap(request.toByteArray());
        long position;
        final var maxRetryAttempts = 3;
        var retryAttempts = 0;
        while (true) {
            position = publication.offer(buffer);
            if (position > 0) {
                if (log.isDebugEnabled()) {
                    log.debug("request has been sent successfully to aeron");
                }
                future.complete(null);
                break;
            } else if (position == NOT_CONNECTED || position == BACK_PRESSURED || position == ADMIN_ACTION) {
                log.warn("we cannot publish request to Aeron right now. Response code: {}. We will retry it", position);
                Thread.onSpinWait();
                retryAttempts++;
                if (retryAttempts >= maxRetryAttempts) {
                    log.warn("all retry attempts were exhausted");
                    future.completeExceptionally(new AeronPublishRequestException("Unable to send request to aeron. All retry attempts were exhausted"));
                    break;
                }
            } else {
                final var errorMsg = String.format("failed to publish request to aeron. Response code: %s", position);
                log.error(errorMsg);
                future.completeExceptionally(new AeronPublishRequestException(errorMsg));
                break;
            }
        }
        return future;
    }

    @Override
    public void subscribeOnEvents(Consumer<MessageLite> onEventConsumer, Parser<? extends MessageLite> parser, String source) {
        final var subscription = getSubscription(source);
        if (subscription == null) {
            throw new AeronSubscribeException("unable to register subscription");
        }
        final var fragmentAssembler = new FragmentAssembler(new ProtoBufFragmentHandler(onEventConsumer, parser));
        final var subscriberIdleStrategy = AeronFactory.takeIdleStrategy(EnvVars.getValue("aeron.mediaDriver.receiver.idleStrategy", "NoOp"));
        subscriberIsPolling.set(true);
        subscriberPollingService.submit(() -> {
            while (subscriberIsPolling.get()) {
                int fragmentsRead = subscription.poll(fragmentAssembler, FRAGMENTS_LIMIT);
                subscriberIdleStrategy.idle(fragmentsRead);
            }
        });
    }


    @Nullable
    protected Publication getPublication(String destination) {
        return registeredPublications.computeIfAbsent(destination, __ -> {
            try {
                return aeronClient.addPublication(channel, topicsToStreamIdMap.getInt(destination));
            } catch (Exception ex) {
                log.error("unable to register publication", ex);
                return null;
            }
        });
    }

    @Nullable
    protected Subscription getSubscription(String source) {
        return registeredSubscriptions.computeIfAbsent(source, __ -> {
            try {
                return aeronClient.addSubscription(channel, topicsToStreamIdMap.getInt(source));
            } catch (Exception ex) {
                log.error("unable to register subscription", ex);
                return null;
            }
        });
    }

    @Override
    public void shutdown() {
        subscriberIsPolling.set(false);
        subscriberPollingService.shutdownNow();
        for (Publication publication : registeredPublications.values()) {
            if (!publication.isClosed()) {
                CommonUtil.closeQuietly(publication);
            }
        }
        for (Subscription subscription : registeredSubscriptions.values()) {
            if (!subscription.isClosed()) {
                CommonUtil.closeQuietly(subscription);
            }
        }

        if (!aeronClient.isClosed()) {
            CommonUtil.closeQuietly(aeronClient);
        }
        if (mediaDriver != null) {
            CommonUtil.closeQuietly(mediaDriver);
        }
        bufferTL.remove();
    }
}
