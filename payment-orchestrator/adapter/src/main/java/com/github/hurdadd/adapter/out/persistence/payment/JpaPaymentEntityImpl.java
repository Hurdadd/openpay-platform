package com.github.hurdadd.adapter.out.persistence.payment;

import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentId;
import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.domain.PspReference;
import com.github.hurdadd.adapter.out.persistence.payment.mapper.PaymentMapper;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.EventTypes;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.JpaPaymentOutboxEntityRepository;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.PaymentOutboxEntity;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.PaymentSettledV1Payload;
import com.github.hurdadd.domain.PaymentEvent;
import com.github.hurdadd.domain.PaymentSettled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaPaymentEntityImpl implements PaymentRepository {
    private final JpaPaymentEntityRepository repository;
    private final PaymentMapper paymentMapper;
    private final JpaPaymentOutboxEntityRepository outboxRepository;

    public JpaPaymentEntityImpl(JpaPaymentEntityRepository Repository, PaymentMapper paymentMapper,
                                JpaPaymentOutboxEntityRepository outboxRepository) {
        this.repository = Repository;
        this.paymentMapper = paymentMapper;
        this.outboxRepository = outboxRepository;
    }

    // MANDATORY: payment row and outbox rows must share the caller's transaction; without one,
    // Spring Data would silently commit each save() on its own and bring the dual-write back
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment save(Payment payment) {
        PaymentEntity entity = paymentMapper.toEntity(payment);
        PaymentEntity savedEntity = repository.save(entity);
        for (PaymentEvent event : payment.pullEvents()) {
            outboxRepository.save(toOutbox(event));
        }
        return paymentMapper.toDomain(savedEntity);
    }

    private PaymentOutboxEntity toOutbox(PaymentEvent event) {
        return switch (event) {
            case PaymentSettled e -> new PaymentOutboxEntity(
                    UUID.randomUUID(),
                    e.paymentId().getValue(),
                    EventTypes.PAYMENT_SETTLED_V1,
                    new PaymentSettledV1Payload(
                            e.paymentId().getValue(),
                            e.merchantId().getValue(),
                            // exact or loud: never silently round money on the wire (ADR-0008)
                            e.money().getAmount().movePointRight(e.money().getCurrency().getMinorUnits()).longValueExact(),
                            e.money().getCurrency().name(),
                            e.settledAt().toString()));
        };
    }

    @Override
    public Optional<Payment> findById(PaymentId paymentId) {
        return repository.findById(paymentId.getValue())
                .map(paymentMapper::toDomain);
    }

    @Override
    public Optional<Payment> findByPspReference(PspReference pspReference) {
        return repository.findByPspReference(pspReference.value())
                .map(paymentMapper::toDomain);
    }

    @Override
    public List<Payment> findStuckPending(Instant olderThan, Instant newerThan) {
        return repository.findStuck(List.of(PaymentStatus.CREATED, PaymentStatus.PENDING_PSP, PaymentStatus.AUTHORIZED), olderThan, newerThan)
                .stream()
                .map(paymentMapper::toDomain)
                .toList();
    }
}
