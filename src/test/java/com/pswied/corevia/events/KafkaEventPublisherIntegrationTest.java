package com.pswied.corevia.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

class KafkaEventPublisherIntegrationTest {

    private static final String TOPIC = "kafka-proof-topic";
    private static final String PAYLOAD = "{\"transferId\":\"demo-txn-001\",\"status\":\"SUCCESS\"}";

    @Test
    void publishesMessageToRealKafkaBrokerWhenDockerIsAvailable() {
        assumeTrue(new File("/var/run/docker.sock").exists(), "Docker is not available for the optional Kafka broker proof-of-concept");

        try (KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))) {
            kafka.start();

            Map<String, Object> producerProps = new HashMap<>();
            producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

            KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(producerProps)
            );
            KafkaEventPublisher publisher = new KafkaEventPublisher(kafkaTemplate);

            publisher.publish(TOPIC, "demo-account-123", PAYLOAD);

            Map<String, Object> consumerProps = new HashMap<>();
            consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "corevia-kafka-proof-" + UUID.randomUUID());
            consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
                consumer.subscribe(List.of(TOPIC));
                long deadline = System.currentTimeMillis() + 15_000L;

                while (System.currentTimeMillis() < deadline) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                    for (ConsumerRecord<String, String> record : records) {
                        if (record.key().equals("demo-account-123") && record.value().equals(PAYLOAD)) {
                            assertThat(record.topic()).isEqualTo(TOPIC);
                            return;
                        }
                    }
                }

                throw new AssertionError("Kafka message was not published to topic " + TOPIC);
            }
        }
    }
}
