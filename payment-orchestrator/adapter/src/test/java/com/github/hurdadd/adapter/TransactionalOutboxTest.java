package com.github.hurdadd.adapter;

import com.github.hurdadd.adapter.out.persistence.payment.JpaPaymentEntityImpl;
import com.github.hurdadd.adapter.out.persistence.payment.mapper.PaymentMapper;
import com.github.hurdadd.adapter.out.persistence.payment.outbox.JpaPaymentOutboxEntityRepository;
import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.MerchantId;
import com.github.hurdadd.domain.Money;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.domain.PspReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** PAY-204: the payment row and its outbox row commit or roll back together. */
@DataJpaTest
@Import({JpaPaymentEntityImpl.class, PaymentMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED) // each test drives its own real transactions
class TransactionalOutboxTest {

    @Autowired JpaPaymentEntityImpl payments;
    @Autowired JpaPaymentOutboxEntityRepository outbox;
    @Autowired PlatformTransactionManager txManager;
    @Autowired jakarta.persistence.EntityManager em;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private UUID seedAuthorized() {
        Payment p = Payment.create(new Money(new BigDecimal("10000"), Currency.IRR),
                MerchantId.from(UUID.randomUUID()), new PspReference("psp-" + UUID.randomUUID()));
        p.markPending();
        p.authorize();
        tx().executeWithoutResult(s -> payments.save(p));
        return p.getPaymentId().getValue();
    }

    private long outboxRows(UUID paymentId) {
        return outbox.findAll().stream().filter(o -> o.getPaymentId().equals(paymentId)).count();
    }

    private Payment load(UUID id) {
        return tx().execute(s -> payments.findById(com.github.hurdadd.domain.PaymentId.from(id)).orElseThrow());
    }

    @Test
    void settled_writes_exactly_one_outbox_row_in_the_same_transaction() {
        UUID id = seedAuthorized();

        tx().executeWithoutResult(s -> {
            Payment p = payments.findById(com.github.hurdadd.domain.PaymentId.from(id)).orElseThrow();
            p.settle();
            payments.save(p);
        });

        assertEquals(PaymentStatus.SETTLED, load(id).getPaymentStatus());
        assertEquals(1, outboxRows(id));
    }

    @Test
    void exception_after_outbox_insert_and_before_commit_rolls_back_both() {
        UUID id = seedAuthorized();

        assertThrows(IllegalStateException.class, () -> tx().executeWithoutResult(s -> {
            Payment p = payments.findById(com.github.hurdadd.domain.PaymentId.from(id)).orElseThrow();
            p.settle();
            payments.save(p);
            em.flush();                // force the payment UPDATE + outbox INSERT to the DB before we blow up
            throw new IllegalStateException("boom before commit");
        }));

        assertEquals(PaymentStatus.AUTHORIZED, load(id).getPaymentStatus());
        assertEquals(0, outboxRows(id));
    }

    @Test
    void save_outside_a_transaction_is_refused() {
        Payment p = Payment.create(new Money(new BigDecimal("1"), Currency.IRR),
                MerchantId.from(UUID.randomUUID()), new PspReference("psp-x"));
        assertThrows(IllegalTransactionStateException.class, () -> payments.save(p));
    }
}
