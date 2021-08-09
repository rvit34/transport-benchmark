package org.bench.transports.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.bench.transports.utils.EnvVars;

import java.util.Properties;
import java.util.UUID;

public class KafkaFactory {

    public KafkaProducer<byte[], byte[]> createProducer() { return new KafkaProducer<>(producerProperties()); }

    public KafkaConsumer<byte[], byte[]> createConsumer() { return new KafkaConsumer<byte[], byte[]>(consumerProperties());}

    private static Properties producerProperties() {
        final var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, EnvVars.getValue("kafka.bootstrapServers", "localhost:9092"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, EnvVars.getValue("kafka.producers.acks", "all"));
        props.put(ProducerConfig.LINGER_MS_CONFIG, EnvVars.getValue("kafka.producers.lingerMs", "0"));
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, EnvVars.getValue("kafka.producers.batchSize", "16384"));
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, EnvVars.getValue("kafka.producers.bufferMemory", "33554432"));
        return props;
    }

    private static Properties consumerProperties() {
        final var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, EnvVars.getValue("kafka.bootstrapServers", "localhost:9092"));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, EnvVars.getValue("kafka.consumers.fetchMinBytes", "1024000"));
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, EnvVars.getValue("kafka.consumers.fetchMaxWaitMs", "50"));
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, EnvVars.getValue("kafka.consumers.maxPollIntervalMs", "10000"));
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, EnvVars.getValue("kafka.consumers.maxPollRecords", "500"));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, EnvVars.getValue("kafka.consumers.enableAutoCommit", "false"));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, EnvVars.getValue("kafka.consumers.autoOffsetReset", "latest"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, EnvVars.getValue("kafka.consumers.groupId", "load-generator-").concat(UUID.randomUUID().toString()));
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, EnvVars.getValue("kafka.consumers.sessionTimeoutMs", "10000"));
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, EnvVars.getValue("kafka.consumers.heartbeatIntervalMs", "3000"));
        return props;
    }

}
