package com.github.hurdadd.application;

import com.github.hurdadd.adapter.psp.FlakyPsp;
import com.github.hurdadd.adapter.psp.ResilientPspClient;
import com.github.hurdadd.adapter.psp.exception.PspException;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.MerchantReference;
import com.github.hurdadd.domain.Money;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;


class ResilientPspClientTest {



    @Test
    void circuitBreakerOpensAfterRepeatedFailures() {
        PspClient alwaysFails = new FlakyPsp(1.0);
        ResilientPspClient client = new ResilientPspClient(alwaysFails);

        for (int i = 0; i < 40; i++) {
            try{
                client.authorize(new AuthorizationRequest(Money.of(BigDecimal.valueOf(100), Currency.USD) , MerchantReference.of("merchant-123")));
            } catch (PspException e) {
            }
        }
        assertEquals(client.getCircuitBreaker().getState() , CircuitBreaker.State.OPEN);

    }
}