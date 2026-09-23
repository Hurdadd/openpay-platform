package com.github.hurdadd.application;

import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.adapter.out.persistence.payment.JpaPaymentEntityRepository;
import com.github.hurdadd.adapter.out.persistence.payment.PaymentEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentEntityConcurrencyTest {

    @Autowired
    JpaPaymentEntityRepository repository;

    @Test
    void optimistic_lock_should_allow_only_one_update() throws Exception {

        UUID id = UUID.randomUUID();

        repository.save(
                PaymentEntity.builder()
                        .paymentId(id)
                        .merchantId(UUID.randomUUID())
                        .status(PaymentStatus.CREATED)
                        .amount(BigDecimal.valueOf(100))
                        .currency(Currency.USD)
                        .build()
        );

        CyclicBarrier barrier = new CyclicBarrier(2);

        List<Throwable> errors =
                Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> worker = () -> {

            try {

                PaymentEntity payment =
                        repository.findById(id).orElseThrow();

                barrier.await();

                payment.setStatus(PaymentStatus.AUTHORIZED);

                repository.saveAndFlush(payment);

            } catch (Throwable t) {
                errors.add(t);
            }

            return null;
        };

        Future<?> f1 = executor.submit(worker);
        Future<?> f2 = executor.submit(worker);

        f1.get();
        f2.get();

        executor.shutdown();

        assertEquals(1, errors.size());

        assertTrue(
                errors.getFirst() instanceof
                        ObjectOptimisticLockingFailureException
        );

        PaymentEntity updated =
                repository.findById(id).orElseThrow();

        assertEquals(PaymentStatus.AUTHORIZED,
                updated.getStatus());

        assertEquals(1L, updated.getVersion());
    }
}
