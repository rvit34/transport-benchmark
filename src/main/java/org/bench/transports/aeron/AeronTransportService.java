package org.bench.transports.aeron;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.*;
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
public class AeronTransportService implements TransportService {

    private static final int FRAGMENTS_LIMIT = 1;
    private static final int PUBLICATION_OR_SUBSCRIPTION_CONNECTED_MAX_WAIT_MS = 30000;

    @Nullable
    private final MediaDriver mediaDriver;
    private final Aeron aeron;

    private final String channel = EnvVars.getValue("aeron.channel", "aeron:ipc");
    private final IdleStrategy subscriberIdleStrategy = takeIdleStrategy(EnvVars.getValue("aeron.mediaDriver.receiver.idleStrategy", "NoOp"));

    private final Object2IntArrayMap<String> topicsToStreamIdMap = new Object2IntArrayMap<>(2);
    private final Map<String, Publication> registeredPublications = new ConcurrentHashMap<>();
    private final Map<String, Subscription> registeredSubscriptions = new ConcurrentHashMap<>();

    private final ThreadLocal<UnsafeBuffer> bufferTL = ThreadLocal.withInitial(UnsafeBuffer::new);
    private final AtomicBoolean subscriberIsPolling = new AtomicBoolean();

    private final ExecutorService subscriberPollingService = Executors.newSingleThreadExecutor();

    public AeronTransportService() {
        initStreamIds();
        this.mediaDriver = createAndLaunchMediaDriver();
        this.aeron = createAndConnectAeron();
    }

    @Nullable
    private MediaDriver createAndLaunchMediaDriver() {
        final boolean mediaDriverRequired = Boolean.parseBoolean(EnvVars.getValue("aeron.mediaDriver.required", "true"));
        if (!mediaDriverRequired) return null;
        final var threadingMode = EnvVars.getValue("aeron.mediaDriver.threadingMode", "DEDICATED");
        final boolean isEmbedded = Boolean.parseBoolean(EnvVars.getValue("aeron.mediaDriver.embedded", "true"));
        final String dirName = EnvVars.getValue("aeron.mediaDriver.dirName", "/dev/shm/aeron-poc");
        final boolean deleteDirOnShutdown = Boolean.parseBoolean(EnvVars.getValue("aeron.mediaDriver.deleteDirOnShutdown", "true"));
        final var ctx = new MediaDriver.Context()
                .termBufferSparseFile(false)
                .useWindowsHighResTimer(true)
                .aeronDirectoryName(dirName)
                .dirDeleteOnShutdown(deleteDirOnShutdown)
                .threadingMode(ThreadingMode.valueOf(threadingMode))
                .senderIdleStrategy(takeIdleStrategy(EnvVars.getValue("aeron.mediaDriver.sender.idleStrategy", "NoOp")))
                .receiverIdleStrategy(subscriberIdleStrategy)
                .conductorIdleStrategy(takeIdleStrategy(EnvVars.getValue("aeron.mediaDriver.conductor.idleStrategy", "BusySpin")));
        if (isEmbedded) {
            return MediaDriver.launchEmbedded(ctx);
        } else {
            return MediaDriver.launch(ctx);
        }
    }

    private Aeron createAndConnectAeron() {
        final boolean isEmbedded = Boolean.parseBoolean(EnvVars.getValue("aeron.mediaDriver.embedded", "true"));
        final String dirName = EnvVars.getValue("aeron.mediaDriver.dirName", "/dev/shm/aeron-poc");
        log.info("aeron directory name: {}", dirName);
        final var ctx = new Aeron.Context();
        if (mediaDriver == null) {
            ctx.aeronDirectoryName(dirName);
        } else if (isEmbedded) {
            ctx.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        } else {
            ctx.aeronDirectoryName(dirName);
        }
        return Aeron.connect(ctx);
    }

    private void initStreamIds() {
        topicsToStreamIdMap.put(TransportService.LOAD_TEST_TOPIC, 1);
    }

    private IdleStrategy takeIdleStrategy(String key) {
        switch (key) {
            case "NoOp": return new NoOpIdleStrategy();
            case "BusySpin": return new BusySpinIdleStrategy();
            case "Yield": return new YieldingIdleStrategy();
            case "Sleep": return new SleepingIdleStrategy();
            default: return new NoOpIdleStrategy();
        }
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
        subscriberIsPolling.set(true);
        subscriberPollingService.submit(() -> {
            while (subscriberIsPolling.get()) {
                int fragmentsRead = subscription.poll(fragmentAssembler, FRAGMENTS_LIMIT);
                subscriberIdleStrategy.idle(fragmentsRead);
            }
        });
    }

    @Nullable
    private Publication getPublication(String destination) {
        return registeredPublications.computeIfAbsent(destination, __ -> {
            try {
                return aeron.addPublication(channel, topicsToStreamIdMap.getInt(destination));
            } catch (Exception ex) {
                log.error("unable to register publication", ex);
                return null;
            }
        });
    }

    @Nullable
    private Subscription getSubscription(String source) {
        return registeredSubscriptions.computeIfAbsent(source, __ -> {
            try {
                return aeron.addSubscription(channel, topicsToStreamIdMap.getInt(source));
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

        if (!aeron.isClosed()) {
            CommonUtil.closeQuietly(aeron);
        }
        if (mediaDriver != null) {
            CommonUtil.closeQuietly(mediaDriver);
        }
        bufferTL.remove();
    }
}
