package com.github.hurdadd.adapter.psp;

import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspOutcome;
import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.MerchantReference;
import com.github.hurdadd.domain.Money;
import com.github.hurdadd.domain.PspReference;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlowPspTest {

    private static AuthorizationRequest request(String amount, PspReference ref) {
        return new AuthorizationRequest(new Money(new BigDecimal(amount), Currency.USD), MerchantReference.of("m-1"), ref);
    }

    @Test
    void echoes_the_callers_reference_so_the_reconciler_asks_about_the_same_one() {
        var psp = new SlowPsp();
        var ref = new PspReference("psp_caller-minted");

        var result = psp.authorize(request("100.00", ref));

        assertEquals(ref, result.reference());
        assertEquals(PspOutcome.APPROVED, psp.checkStatus(ref).pspOutcome());
    }

    @Test
    void the_decline_amount_is_declined_and_stays_declined_when_polled() {
        var psp = new SlowPsp();
        var ref = new PspReference("psp_decline-me");

        assertEquals(PspOutcome.DECLINED, psp.authorize(request(SlowPsp.DECLINE_AMOUNT, ref)).pspOutcome());
        assertEquals(PspOutcome.DECLINED, psp.checkStatus(ref).pspOutcome());
    }
}
