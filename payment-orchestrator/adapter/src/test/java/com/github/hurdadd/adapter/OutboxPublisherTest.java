package com.github.hurdadd.adapter;

import com.github.hurdadd.adapter.out.messaging.outbox.OutboxPublisher;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.EventTypes;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.JpaPaymentOutboxEntityRepository;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.PaymentOutboxEntity;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.PaymentSettledV1Payload;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    @Test
    void publish_sends_json_envelope_built_from_the_row() {
        var eventId = UUID.randomUUID();
        var paymentId = UUID.randomUUID();
        var payload = new PaymentSettledV1Payload(
                paymentId, UUID.randomUUID(), 10000L, "IRR", Instant.now().toString());
        var entity = new PaymentOutboxEntity(
                eventId, paymentId, EventTypes.PAYMENT_SETTLED_V1, payload);

        var repository = mock(JpaPaymentOutboxEntityRepository.class);
        when(repository.findUnpublished()).thenReturn(List.of(entity));

        var kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        var publisher = new OutboxPublisher(repository, kafkaTemplate, new ObjectMapper() , "payment-events");

        publisher.publish();

        var jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("payment-events"), eq(paymentId.toString()), jsonCaptor.capture());

        var json = jsonCaptor.getValue();

        assertTrue(json.startsWith("{"), "value باید JSON باشد ولی این است: " + json);
        assertTrue(json.contains("\"eventId\":\"" + eventId + "\""), "eventId از row نیامده");
        assertTrue(json.contains("\"eventType\":\"PaymentSettled.v1\""), "eventType باید wireFormat باشد");
        assertTrue(json.contains("\"data\":{"), "data باید آبجکت باشد، نه رشته‌ی JSON");
        assertTrue(json.contains("\"amountMinor\":10000"), "amountMinor در data نیست");
        assertFalse(json.contains("\\\""), "data دوبار سریالایز شده است");
    }

    @Test
    void events_of_one_payment_share_a_key_and_therefore_a_partition() {
        var paymentId = UUID.randomUUID();
        var payload = new PaymentSettledV1Payload(paymentId, UUID.randomUUID(), 1L, "IRR", Instant.now().toString());
        var repository = mock(JpaPaymentOutboxEntityRepository.class);
        when(repository.findUnpublished()).thenReturn(List.of(
                new PaymentOutboxEntity(UUID.randomUUID(), paymentId, EventTypes.PAYMENT_SETTLED_V1, payload),
                new PaymentOutboxEntity(UUID.randomUUID(), paymentId, EventTypes.PAYMENT_FAILED_V1, payload)));

        var kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new OutboxPublisher(repository, kafkaTemplate, new ObjectMapper(), "payment-events").publish();

        var keys = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(eq("payment-events"), keys.capture(), anyString());
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));

        // Kafka's default partitioning is murmur2(keyBytes) % numPartitions — same key, same partition
        int partitions = 12;
        int p0 = partitionOf(keys.getAllValues().get(0), partitions);
        int p1 = partitionOf(keys.getAllValues().get(1), partitions);
        assertEquals(p0, p1);
    }

    private static int partitionOf(String key, int partitions) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return org.apache.kafka.common.utils.Utils.toPositive(org.apache.kafka.common.utils.Utils.murmur2(bytes)) % partitions;
    }
}
