package com.github.hurdadd.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

}