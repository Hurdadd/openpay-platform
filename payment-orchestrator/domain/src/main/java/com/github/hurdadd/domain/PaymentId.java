package com.github.hurdadd.domain;

import java.util.Objects;
import java.util.UUID;

public final class PaymentId {
    private final UUID value;

    private PaymentId(UUID value) {
        this.value = value;
    }

    static PaymentId generate(){
        return new PaymentId(UUID.randomUUID());
    }
    public static PaymentId from(UUID uuid){
        Objects.requireNonNull(uuid);
        return new PaymentId(uuid);
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaymentId paymentId = (PaymentId) o;
        return Objects.equals(value, paymentId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }


}
