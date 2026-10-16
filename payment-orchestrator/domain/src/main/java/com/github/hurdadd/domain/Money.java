package com.github.hurdadd.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class Money{
    private  final  BigDecimal amount;

    private  final  Currency currency;



    public Money(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        try {
            this.amount = amount.setScale(currency.getMinorUnits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                    "amount has more than " + currency.getMinorUnits()
                            + " decimal places for " + currency, e);
        }
        this.currency = currency;
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return Objects.equals(amount, money.amount) && currency == money.currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }
}
