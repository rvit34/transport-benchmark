package org.bench.transports.aeron;

import io.aeron.Aeron;
import io.aeron.RethrowingErrorHandler;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.AtomicCounter;
import org.bench.transports.utils.EnvVars;

import javax.annotation.Nullable;

@Slf4j
public class AeronFactory {

    private static final String AERON_DIR_NAME = EnvVars.getValue("aeron.dirName", "/dev/shm/aeron-poc");

    @Nullable
    MediaDriver createAndLaunchMediaDriver() {
        final boolean mediaDriverEnabled = Boolean.parseBoolean(EnvVars.getValue("aeron.mediaDriver.enabled", "false"));
        if (!mediaDriverEnabled) return null;
        final var threadingMode = EnvVars.getValue("aeron.mediaDriver.threadingMode", "DEDICATED");
        final boolean isEmbedded = Boolean.parseBoolean(EnvVars.getValue("aeron.mediaDriver.embedded", "true"));
        final boolean deleteDirOnShutdown = Boolean.parseBoolean(EnvVars.getValue("aeron.mediaDriver.deleteDirOnShutdown", "true"));
        final var ctx = new MediaDriver.Context()
                .termBufferSparseFile(false)
                .useWindowsHighResTimer(true)
                .aeronDirectoryName(AERON_DIR_NAME)
                .dirDeleteOnShutdown(deleteDirOnShutdown)
                .threadingMode(ThreadingMode.valueOf(threadingMode))
                .spiesSimulateConnection(Boolean.parseBoolean(EnvVars.getValue("aeron.mediaDriver.spies.simulate.connection", "false")))
                .ipcMtuLength(Integer.parseInt(EnvVars.getValue("aeron.mediaDriver.ipc.mtu.length","8192")))
                .mtuLength(Integer.parseInt(EnvVars.getValue("aeron.mediaDriver.mtu.length","8192")))
                .socketSndbufLength(Integer.parseInt(EnvVars.getValue("aeron.mediaDriver.socket.so_sndbuf", "2097152")))
                .socketRcvbufLength(Integer.parseInt(EnvVars.getValue("aeron.mediaDriver.socket.so_rcvbuf", "2097152")))
                .initialWindowLength(Integer.parseInt(EnvVars.getValue("aeron.mediaDriver.rcv.initial.window.length", "2097152")))
                .senderIdleStrategy(takeIdleStrategy(EnvVars.getValue("aeron.mediaDriver.sender.idleStrategy", "NoOp")))
                .receiverIdleStrategy(takeIdleStrategy(EnvVars.getValue("aeron.mediaDriver.receiver.idleStrategy", "NoOp")))
                .conductorIdleStrategy(takeIdleStrategy(EnvVars.getValue("aeron.mediaDriver.conductor.idleStrategy", "BusySpin")));
        if (isEmbedded) {
            return MediaDriver.launchEmbedded(ctx);
        } else {
            return MediaDriver.launch(ctx);
        }
    }

   Aeron createAndConnectAeronClient(@Nullable MediaDriver mediaDriver) {
        final boolean isEmbedded = Boolean.parseBoolean(EnvVars.getValue("aeron.mediaDriver.embedded", "true"));
        log.info("aeron directory name: {}", AERON_DIR_NAME);
        final var ctx = new Aeron.Context();
        if (mediaDriver == null) {
            ctx.aeronDirectoryName(AERON_DIR_NAME);
        } else if (isEmbedded) {
            ctx.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        } else {
            ctx.aeronDirectoryName(AERON_DIR_NAME);
        }
        ctx.subscriberErrorHandler(new RethrowingErrorHandler());
        ctx.idleStrategy(takeIdleStrategy(EnvVars.getValue("aeron.client.idleStrategy", "NoOp")));
        ctx.awaitingIdleStrategy(takeIdleStrategy(EnvVars.getValue("aeron.client.awaitingIdleStrategy", "BusySpin")));
        ctx.preTouchMappedMemory(Boolean.parseBoolean(EnvVars.getValue("aeron.client.preTouchMappedMemory", "true")));
        return Aeron.connect(ctx);
    }

    Archive createAndLaunchArchive(Aeron aeron, @Nullable MediaDriver mediaDriver) {
        final var archiveThreadingMode = EnvVars.getValue("aeron.archive.threading.mode", "DEDICATED");
        final var ctx = new Archive.Context()
            .aeron(aeron)
            .errorCounter(new AtomicCounter(new UnsafeBuffer(new byte[1024*1024]), Archive.Configuration.ARCHIVE_ERROR_COUNT_TYPE_ID))
            .archiveDirectoryName(EnvVars.getValue("aeron.archive.dir", "./build/aeron/archive"))
            .aeronDirectoryName(AERON_DIR_NAME)
            .deleteArchiveOnStart(Boolean.parseBoolean(EnvVars.getValue("aeron.archive.deleteOnStart", "true")))
            .threadingMode(ArchiveThreadingMode.valueOf(archiveThreadingMode))
            .fileSyncLevel(Integer.parseInt(EnvVars.getValue("aeron.archive.file.sync.level", "0")))
            .catalogFileSyncLevel(Integer.parseInt(EnvVars.getValue("aeron.archive.catalog.file.sync.level", "0")))
            .segmentFileLength(Integer.parseInt(EnvVars.getValue("aeron.archive.segment.file.length", "1073741824")))
            .fileIoMaxLength(Integer.parseInt(EnvVars.getValue("aeron.archive.file.io.max.length", "1048576")))
            .recorderIdleStrategySupplier(() -> takeIdleStrategy(EnvVars.getValue("aeron.archive.recorderIdleStrategy", "NoOp")))
            .replayerIdleStrategySupplier(() -> takeIdleStrategy(EnvVars.getValue("aeron.archive.replayerIdleStrategy", "NoOp")));

        if (mediaDriver != null) {
            ctx.mediaDriverAgentInvoker(mediaDriver.sharedAgentInvoker());
        }

        return Archive.launch(ctx);
    }

    AeronArchive createAndConnectAeronArchiveClient(Aeron aeron) {
        final var ctx = new AeronArchive.Context()
                .aeron(aeron)
                .aeronDirectoryName(AERON_DIR_NAME)
                .idleStrategy(takeIdleStrategy(EnvVars.getValue("aeron.archive.client.idleStrategy", "BackOff")))
                .errorHandler(th -> log.error("aeron archive client error", th));
        return AeronArchive.connect(ctx);
    }

    static IdleStrategy takeIdleStrategy(String key) {
        switch (key) {
            case "BusySpin": return new BusySpinIdleStrategy();
            case "Yield": return new YieldingIdleStrategy();
            case "Sleep": return new SleepingIdleStrategy();
            case "BackOff": return new BackoffIdleStrategy();
            default: return new NoOpIdleStrategy();
        }
    }
}
