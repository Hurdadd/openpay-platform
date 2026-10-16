package com.github.hurdadd.ledger;

import com.github.hurdadd.adapter.out.persistence.ledger.JdbcLedgerRepository;
import com.github.hurdadd.application.port.in.RecordLedgerEntryCommand;
import com.github.hurdadd.application.service.RecordLedgerEntryService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled("Needs a Kafka broker image; this environment's registry blocks kafka images (apache/kafka RBAC-denied, confluent pull too slow). Runs in CI. AC5 is covered meanwhile by RecordLedgerEntryServiceTest double-processing on real Postgres plus PaymentSettledListenerTest ack ordering.")
class LedgerKafkaRedeliveryTest {
    static final String TOPIC = "ledger-redelivery-test";
    static final String GROUP = "ledger-redelivery";
    static final String PAYMENT = "11111111-1111-1111-1111-111111111111";

    static final RecordLedgerEntryCommand COMMAND = new RecordLedgerEntryCommand(
            UUID.fromString("33333333-3333-3333-3333-333333333333"), UUID.fromString(PAYMENT),
            UUID.fromString("22222222-2222-2222-2222-222222222222"), Currency.getInstance("IRR"), 10000);

    static final String ENVELOPE = """
            {"eventId":"33333333-3333-3333-3333-333333333333","eventType":"PaymentSettled.v1","data":{"paymentId":"%s","merchantId":"22222222-2222-2222-2222-222222222222","amountMinor":10000,"currency":"IRR","settledAt":"2026-09-21T10:00:00Z"}}"""
            .formatted(PAYMENT);

    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.9.0");
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static RecordLedgerEntryService service;

    @BeforeAll
    static void start() throws Exception {
        kafka.start();
        postgres.start();
        jdbc = LedgerTestSupport.migratedJdbcTemplate(postgres);
        service = new RecordLedgerEntryService(new JdbcLedgerRepository(jdbc), LedgerTestSupport.txRunner(jdbc));

        try (var admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(60, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void stop() {
        kafka.stop();
        postgres.stop();
    }

    @Test
    void crashed_consumer_redelivers_and_second_processing_changes_nothing() throws Exception {
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"))) {
            producer.send(new ProducerRecord<>(TOPIC, PAYMENT, ENVELOPE)).get(60, TimeUnit.SECONDS);
        }

        try (var consumer = newConsumer()) {
            consumer.subscribe(List.of(TOPIC));
            var records = consumer.poll(Duration.ofSeconds(60));
            assertEquals(1, records.count());
            service.record(COMMAND);
        }

        try (var consumer = newConsumer()) {
            consumer.subscribe(List.of(TOPIC));
            var records = consumer.poll(Duration.ofSeconds(60));
            assertEquals(1, records.count());
            service.record(COMMAND);
            consumer.commitSync();
        }

        assertEquals(1, LedgerTestSupport.count(jdbc, "journal_entry"));
        assertEquals(2, LedgerTestSupport.count(jdbc, "posting"));
    }

    private static KafkaConsumer<String, String> newConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, GROUP,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
    }
}
