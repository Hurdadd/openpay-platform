package com.github.hurdadd.application.service;

import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.domain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PspCallbackServiceTest {

    // in-memory fake keyed by PSP reference — enough to prove idempotency without a database
    static class InMemoryPaymentRepository implements PaymentRepository {
        final Map<String, Payment> byPspReference = new HashMap<>();
        int saveCount = 0;

        void seed(Payment payment) {
            byPspReference.put(payment.getPspReference().value(), payment);
        }

        @Override
        public Payment save(Payment payment) {
            saveCount++;
            byPspReference.put(payment.getPspReference().value(), payment);
            return payment;
        }

        @Override
        public Optional<Payment> findById(PaymentId paymentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Payment> findByPspReference(PspReference pspReference) {
            return Optional.ofNullable(byPspReference.get(pspReference.value()));
        }

        @Override
        public List<Payment> findStuckPending(Instant olderThan, Instant newerThan) {
            throw new UnsupportedOperationException();
        }
    }

    private Payment pendingPayment(PspReference ref) {
        Payment payment = Payment.create(new Money(new BigDecimal("100.00"), Currency.USD),
                MerchantId.from(UUID.randomUUID()));
        payment.markPending(ref);
        return payment;
    }

    @Test
    void same_callback_delivered_three_times_applies_effect_once() {
        PspReference ref = new PspReference("psp-ref-1");
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        repository.seed(pendingPayment(ref));
        PspCallbackService service = new PspCallbackService(repository);

        service.handle(ref, PaymentStatus.AUTHORIZED);
        service.handle(ref, PaymentStatus.AUTHORIZED);
        service.handle(ref, PaymentStatus.AUTHORIZED);

        assertEquals(PaymentStatus.AUTHORIZED, repository.findByPspReference(ref).orElseThrow().getPaymentStatus());
        assertEquals(1, repository.saveCount);
    }

    @Test
    void out_of_order_callback_is_ignored_not_applied() {
        PspReference ref = new PspReference("psp-ref-2");
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        Payment payment = pendingPayment(ref);
        payment.authorize();
        payment.settle();
        repository.seed(payment);
        PspCallbackService service = new PspCallbackService(repository);

        // a stale AUTHORIZED callback arrives after we already reached the terminal SETTLED state
        service.handle(ref, PaymentStatus.AUTHORIZED);

        assertEquals(PaymentStatus.SETTLED, repository.findByPspReference(ref).orElseThrow().getPaymentStatus());
        assertEquals(0, repository.saveCount);
    }

    @Test
    void unknown_reference_does_not_throw_out_of_the_handler() {
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        PspCallbackService service = new PspCallbackService(repository);

        org.junit.jupiter.api.Assertions.assertThrows(PaymentNotFoundException.class,
                () -> service.handle(new PspReference("unknown"), PaymentStatus.AUTHORIZED));
    }
}
