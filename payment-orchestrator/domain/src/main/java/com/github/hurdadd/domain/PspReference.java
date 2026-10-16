package com.github.hurdadd.domain;

import java.util.Objects;

public record PspReference(
        String value
) {
    public PspReference {
        Objects.requireNonNull(value , "PspReference value cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("PspReference value cannot be blank");
        }

    }

    // minted before the PSP call and sent to it as its idempotency key
    public static PspReference generate() {
        return new PspReference("psp_" + java.util.UUID.randomUUID());
    }
}
