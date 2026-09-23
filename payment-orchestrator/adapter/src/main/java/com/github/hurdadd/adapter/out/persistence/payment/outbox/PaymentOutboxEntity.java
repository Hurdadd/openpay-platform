package com.github.hurdadd.adapter.out.persistence.payment.outbox;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_outbox")
public class PaymentOutboxEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Convert(converter= EventTypeConverter.class)
    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private EventTypes eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private PaymentSettledV1Payload payload;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected PaymentOutboxEntity() {
    }

    public PaymentOutboxEntity(
            UUID id,
            UUID paymentId,
            EventTypes eventType,
            PaymentSettledV1Payload payload
    ) {
        this.id = id;
        this.paymentId = paymentId;
        this.eventType = eventType;
        this.published = false;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished(Instant publishedAt) {
        this.published = true;
        this.publishedAt = publishedAt;
    }

    public UUID getId() {
        return id;
    }

    public EventTypes getEventType() {
        return eventType;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentSettledV1Payload getPayload() {
        return payload;
    }
}