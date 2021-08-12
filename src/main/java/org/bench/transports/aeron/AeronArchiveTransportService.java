package org.bench.transports.aeron;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import io.aeron.ChannelUri;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.archive.Archive;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.RecordingDescriptorConsumer;
import io.aeron.archive.codecs.SourceLocation;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.MutableLong;
import org.bench.transports.utils.CommonUtil;
import org.bench.transports.utils.EnvVars;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.function.Consumer;

@Slf4j
public class AeronArchiveTransportService extends BasicAeronTransportService {

    private static final int REPLY_STREAM_OFFSET = 1000;

    private static final boolean needToRunEmbeddedArchive = Boolean.parseBoolean(EnvVars.getValue("aeron.archive.launchEmbedded", "false"));
    @Nullable
    private Archive archive;
    private final AeronArchive archiveClient;

    public AeronArchiveTransportService() {
        super();
        if (needToRunEmbeddedArchive) {
            archive = aeronFactory.createAndLaunchArchive(aeronClient, mediaDriver);
            log.info("aeron archive launched embeddable. Archive directory: {}", archive.context().archiveDir().getAbsolutePath());
        }
        archiveClient = aeronFactory.createAndConnectAeronArchiveClient(aeronClient);
        log.info("aeron archive client launched");
        for (int streamId : topicsToStreamIdMap.values()) {
            archiveClient.startRecording(channel, streamId, SourceLocation.LOCAL);
        }
        log.info("recordings started");
    }

    @Override
    public void subscribeOnEvents(Consumer<MessageLite> onEventConsumer, Parser<? extends MessageLite> parser, String source) {
        subscriberPollingService.submit(() -> {
            final var subscriberIdleStrategy = AeronFactory.takeIdleStrategy(EnvVars.getValue("aeron.archive.client.idleStrategy", "NoOp"));
            final int sourceStreamId = topicsToStreamIdMap.getInt(source);
            long recordingId;
            while ((recordingId = findLatestRecording(channel, sourceStreamId)) == -1) {
                subscriberIdleStrategy.idle();
            }
            final long position = 0L; // reply from start(for test it's ok, as we always rm archive dir on shutdown)
            final long length = Long.MAX_VALUE;
            final int replayStreamId = sourceStreamId + REPLY_STREAM_OFFSET;
            final long sessionId = archiveClient.startReplay(recordingId, position, length, channel, replayStreamId);
            final String readChannel = ChannelUri.addSessionId(channel, (int) sessionId);
            final Subscription subscription = aeronClient.addSubscription(readChannel, replayStreamId);

            final var fragmentAssembler = new FragmentAssembler(new ProtoBufFragmentHandler(onEventConsumer, parser));

            subscriberIsPolling.set(true);
            while (subscriberIsPolling.get()) {
                int fragmentsRead = subscription.poll(fragmentAssembler, FRAGMENTS_LIMIT);
                subscriberIdleStrategy.idle(fragmentsRead);
            }
        });
     }

    private long findLatestRecording(String channel, int stream)
    {
        final MutableLong lastRecordingId = new MutableLong();
        final RecordingDescriptorConsumer consumer =
                (controlSessionId, correlationId, recordingId,
                 startTimestamp, stopTimestamp, startPosition,
                 stopPosition, initialTermId, segmentFileLength,
                 termBufferLength, mtuLength, sessionId,
                 streamId, strippedChannel, originalChannel,
                 sourceIdentity) -> lastRecordingId.set(recordingId);

        final long fromRecordingId = 0L;
        final int recordCount = 100;
        final int foundCount = archiveClient.listRecordingsForUri(fromRecordingId, recordCount, channel, stream, consumer);

        if (foundCount == 0)
        {
            return -1;
        }
        return lastRecordingId.get();
    }

    @Override
    public void shutdown() {
        log.info("shutting down transport...");
        for (int streamId : topicsToStreamIdMap.values()) {
            archiveClient.stopRecording(channel, streamId);
        }
        CommonUtil.closeQuietly(archiveClient);
        if (archive != null) {
            CommonUtil.closeQuietly(archive);
        }
        // delete archive dir (for clean tests only)
        try {
            CommonUtil.removeDirectoryRecursively(EnvVars.getValue("aeron.archive.dir"));
        } catch (IOException ex) {
            log.error("could not properly clean up aeron archive folder", ex);
        }
        super.shutdown(); // shutdown md(if launched) and its client
        log.info("transport has been shut down successfully");
    }
}
