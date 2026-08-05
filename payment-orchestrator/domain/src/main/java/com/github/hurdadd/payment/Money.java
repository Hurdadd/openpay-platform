package com.github.hurdadd.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class Money{
    private  final  BigDecimal amount;

    private  final  Currency currency;



    public Money(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("amount cannot be negative");
        }
        this.amount = amount.setScale(currency.getMinorUnits(), RoundingMode.UNNECESSARY);
        this.currency = currency ;

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
