package com.github.hurdadd.adapter.out.persistence.payment;

import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentId;
import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.domain.PspReference;
import com.github.hurdadd.adapter.out.persistence.payment.mapper.PaymentMapper;
import com.github.hurdadd.application.port.out.PaymentRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaPaymentEntityImpl implements PaymentRepository {
    private final JpaPaymentEntityRepository repository;
    private final PaymentMapper paymentMapper;

    public JpaPaymentEntityImpl(JpaPaymentEntityRepository Repository, PaymentMapper paymentMapper) {
        this.repository = Repository;
        this.paymentMapper = paymentMapper;
    }

    @Override
    public Payment save(Payment payment) {
        PaymentEntity entity = paymentMapper.toEntity(payment);
        PaymentEntity savedEntity = repository.save(entity);
        return paymentMapper.toDomain(savedEntity);
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
        return repository.findStuck(PaymentStatus.PENDING_PSP, olderThan, newerThan)
                .stream()
                .map(paymentMapper::toDomain)
                .toList();
    }
}
