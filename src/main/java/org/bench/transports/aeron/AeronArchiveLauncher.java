package org.bench.transports.aeron;

import lombok.extern.slf4j.Slf4j;
import org.bench.transports.utils.CommonUtil;

@Slf4j
public class AeronArchiveLauncher {
    public static void main(String[] args) {
        final var aeronFactory = new AeronFactory();
        final var aeronClient = aeronFactory.createAndConnectAeronClient(null);
        final var aeronArchive = aeronFactory.createAndLaunchArchive(aeronClient, null);
        log.info("aeron archive process started. Archive directory: {}", aeronArchive.context().archiveDir().getAbsolutePath());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down aeron archive process...");
            CommonUtil.closeQuietly(aeronArchive);
            CommonUtil.closeQuietly(aeronClient);
        }));
    }
}
