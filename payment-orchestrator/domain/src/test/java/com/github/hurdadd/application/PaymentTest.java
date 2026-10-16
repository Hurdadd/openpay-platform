package com.github.hurdadd.application;

import com.github.hurdadd.domain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PaymentTest {

    private Payment paymentHelper(){
        Money m = new Money(new BigDecimal("10000") , Currency.IRR);
        MerchantId merchantId = MerchantId.from(UUID.randomUUID());
        return Payment.create(m , merchantId, new PspReference("psp-ref-1"));
    }

    @Test
    void happy_path_reaches_settled() {
        Payment p = paymentHelper();
        assertEquals(PaymentStatus.CREATED, p.getPaymentStatus());

        p.markPending();
        assertEquals(PaymentStatus.PENDING_PSP, p.getPaymentStatus());

        p.authorize();
        assertEquals(PaymentStatus.AUTHORIZED, p.getPaymentStatus());

        p.settle();
        assertEquals(PaymentStatus.SETTLED, p.getPaymentStatus());
    }

    @Test
    void settled_cannot_go_back_to_pending() {
        Payment p = paymentHelper();
        p.markPending();
        p.authorize();
        p.settle();
        assertThrows(IllegalStateException.class, () -> p.markPending());

    }

    @Test
    void created_cannot_settle_directly() {
        Payment p = paymentHelper();
        assertThrows(IllegalStateException.class, p::settle);
    }

    @Test
    void settle_records_exactly_one_event_and_pulling_drains_it() {
        Payment p = paymentHelper();
        p.markPending();
        p.authorize();
        assertEquals(0, p.pullEvents().size());

        p.settle();

        var events = p.pullEvents();
        assertEquals(1, events.size());
        assertEquals(p.getPaymentId(), ((PaymentSettled) events.get(0)).paymentId());
        assertEquals(0, p.pullEvents().size());
    }

    @Test
    void failed_settle_records_no_event() {
        Payment p = paymentHelper();
        assertThrows(IllegalStateException.class, p::settle);
        assertEquals(0, p.pullEvents().size());
    }
}
