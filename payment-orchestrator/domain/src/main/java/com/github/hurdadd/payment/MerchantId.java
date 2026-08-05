package com.github.hurdadd.payment;

import java.util.Objects;
import java.util.UUID;

public final class MerchantId {
    private final UUID value;

    private MerchantId(UUID value) {
        this.value = value;
    }
    static MerchantId generate(){
        return new MerchantId(UUID.randomUUID());
    }
    public static MerchantId from(UUID uuid){
        Objects.requireNonNull(uuid);
        return new MerchantId(uuid);
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MerchantId that = (MerchantId) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
