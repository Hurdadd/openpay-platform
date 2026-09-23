package com.github.hurdadd.domain;

import java.util.Objects;

public record MerchantReference(
        String value
) {
    public static MerchantReference of (String value) {
        return new MerchantReference(value);
    }
    public MerchantReference {
        Objects.requireNonNull(value , "MerchantReference value cannot be null");

        if (value.isBlank()) {
            throw new IllegalArgumentException("MerchantReference value cannot be null or blank");
        }
    }
}
