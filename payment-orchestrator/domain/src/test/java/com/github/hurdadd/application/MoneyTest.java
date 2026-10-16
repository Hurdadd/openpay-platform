package com.github.hurdadd.application;

import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {



    @Test
    void two_amounts_with_different_scale_are_equal() {
        var m1 = new Money(new BigDecimal("10000"), Currency.USD);
        var m2 = new Money(new BigDecimal("10000.00") , Currency.USD);
        assertEquals(m1 , m2);
    }

    @Test
    void irr_amount_has_zero_scale() {
        var m1 = new Money(new BigDecimal("50000"), Currency.IRR);
        assertEquals(0, m1.getAmount().scale());
    }

    @Test
    void zero_amount_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Money(new BigDecimal("0"), Currency.USD));
    }

    @Test
    void too_many_decimal_places_is_a_domain_violation() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new Money(new BigDecimal("100.999"), Currency.USD));
        assertEquals("amount has more than 2 decimal places for USD", error.getMessage());
    }

    @Test
    void irr_rejects_any_decimal_place() {
        assertThrows(IllegalArgumentException.class,
                () -> new Money(new BigDecimal("50000.5"), Currency.IRR));
    }

}