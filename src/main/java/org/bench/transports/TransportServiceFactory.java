package org.bench.transports;

import lombok.extern.slf4j.Slf4j;
import org.bench.transports.aeron.AeronArchiveTransportService;
import org.bench.transports.aeron.AeronInMemoryTransportService;
import org.bench.transports.chronicle.ChronicleQueueTransportService;
import org.bench.transports.kafka.KafkaFactory;
import org.bench.transports.kafka.KafkaTransportService;
import org.bench.transports.utils.EnvVars;

@Slf4j
public class TransportServiceFactory {
    private TransportServiceFactory(){}

    public static TransportService buildTransportService() {
        final var transport = EnvVars.getValue("application.orderTransport", "kafka");
        TransportService transportService = null;
        if (transport.equals("kafka")) {
            log.info("kafka is used as a transport for order delivery");
            final var kafkaFactory = new KafkaFactory();
            transportService = new KafkaTransportService(
                    kafkaFactory.createConsumer(),
                    kafkaFactory.createProducer()
            );
        }
        if (transport.equals("aeron")) {
            log.info("aeron is used as a transport for order delivery");
            boolean isArchiveEnabled = Boolean.parseBoolean(EnvVars.getValue("aeron.archive.enabled", "false"));
            if (isArchiveEnabled) {
                transportService = new AeronArchiveTransportService();
            } else {
                transportService = new AeronInMemoryTransportService();
            }
        }
        if (transport.equals("chronicle")) {
            log.info("chronicle queue is used as a transport for order delivery");
            transportService = new ChronicleQueueTransportService();
        }
        if (transportService != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(transportService::shutdown));
            return transportService;
        }
        throw new IllegalArgumentException("env variable 'application.orderTransport' is not recognized. Supported transports [kafka,aeron,chronicle]");
    }
}
