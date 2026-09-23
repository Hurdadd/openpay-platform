package com.github.hurdadd.application;

import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.adapter.out.persistence.payment.JpaPaymentEntityRepository;
import com.github.hurdadd.adapter.out.persistence.payment.PaymentEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;


@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentEntityTest {
    @Autowired
   private EntityManager em;

    @Autowired
   private JpaPaymentEntityRepository repository;


    @Test
    public void testPaymentEntityPersistence(){
        PaymentEntity paymentEntity = PaymentEntity.builder()
                .paymentId(UUID.randomUUID())
                .merchantId(UUID.randomUUID())
                .status(PaymentStatus.CREATED)
                .amount(BigDecimal.valueOf(10000))
                .currency(Currency.USD)
                .build();

        repository.saveAndFlush(paymentEntity);
        em.clear();

        PaymentEntity a = repository.findById(paymentEntity.getPaymentId()).orElseThrow();
        em.clear();
        PaymentEntity b = repository.findById(paymentEntity.getPaymentId()).orElseThrow();

        a.setStatus(PaymentStatus.AUTHORIZED);
        repository.saveAndFlush(a);
        b.setStatus(PaymentStatus.SETTLED);

        assertThrows(ObjectOptimisticLockingFailureException.class, () -> repository.saveAndFlush(b));
    }


}
