package com.github.hurdadd.adapter.config;

import com.github.hurdadd.adapter.in.messaging.exception.UnprocessableMessageException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.awaitility.Awaitility.await;

@EmbeddedKafka(partitions = 1)
class KafkaErrorHandlingTest {

    @Test
    void poison_message_goes_to_dlt_without_retry_and_next_message_is_processed(EmbeddedKafkaBroker broker) throws Exception {
        var processed = new CopyOnWriteArrayList<String>();
        var attempts = new CopyOnWriteArrayList<String>();
        send(broker, "poison-topic", "bad", "good");

        runListener(broker, "poison-topic", new FixedBackOff(10, 5), record -> {
            attempts.add(record.value());
            if (record.value().equals("bad")) {
                throw new UnprocessableMessageException("poison");
            }
            processed.add(record.value());
        });

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(1, processed.size()));
        assertEquals("good", processed.getFirst());
        assertEquals(1, attempts.stream().filter("bad"::equals).count(), "non-retryable: exactly one attempt");
        assertEquals("bad", readDlt(broker, "poison-topic.DLT", 1).getFirst());
    }

    @Test
    void transient_failure_is_retried_then_dead_lettered_not_skipped(EmbeddedKafkaBroker broker) throws Exception {
        var attempts = new CopyOnWriteArrayList<String>();
        send(broker, "flaky-topic", "flaky");

        runListener(broker, "flaky-topic", new FixedBackOff(10, 3), record -> {
            attempts.add(record.value());
            throw new IllegalStateException("db down");
        });

        assertEquals("flaky", readDlt(broker, "flaky-topic.DLT", 1).getFirst());
        assertEquals(4, attempts.size(), "1 delivery + 3 retries before the DLT");
    }

    private void send(EmbeddedKafkaBroker broker, String topic, String... values) {
        var props = KafkaTestUtils.producerProps(broker);
        var template = new KafkaTemplate<String, String>(
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer()));
        for (String v : values) {
            template.send(topic, v);
        }
        template.flush();
    }

    private void runListener(EmbeddedKafkaBroker broker, String topic, FixedBackOff backOff, MessageListener<String, String> listener) {
        var producerFactory = new DefaultKafkaProducerFactory<String, String>(
                KafkaTestUtils.producerProps(broker), new StringSerializer(), new StringSerializer());
        var props = KafkaTestUtils.consumerProps(broker, "listener-" + System.nanoTime(), false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var container = new KafkaMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()),
                containerProps(topic, listener));
        container.setCommonErrorHandler(
                KafkaErrorHandlingConfig.errorHandler(new KafkaTemplate<>(producerFactory), backOff));
        container.start();
    }

    private ContainerProperties containerProps(String topic, MessageListener<String, String> listener) {
        var cp = new ContainerProperties(topic);
        cp.setMessageListener(listener);
        cp.setAckMode(ContainerProperties.AckMode.RECORD);
        return cp;
    }

    private java.util.List<String> readDlt(EmbeddedKafkaBroker broker, String dlt, int expected) {
        var props = KafkaTestUtils.consumerProps(broker, "dlt-" + System.nanoTime(), true);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(Collections.singletonList(dlt));
            var values = new java.util.ArrayList<String>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    values.add(r.value());
                }
                return values.size() >= expected;
            });
            return values;
        }
    }
}
