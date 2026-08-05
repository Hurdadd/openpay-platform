package com.github.hurdadd.payment.persistence.payment;

import com.github.hurdadd.payment.Payment;
import com.github.hurdadd.payment.PaymentId;
import com.github.hurdadd.payment.persistence.payment.mapper.PaymentMapper;
import com.github.hurdadd.payment.port.out.PaymentRepository;
import org.springframework.stereotype.Repository;

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
}
