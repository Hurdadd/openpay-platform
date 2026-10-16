package com.github.hurdadd.adapter.in.web.payment.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;


public record CreatePaymentRequest(
        @NotNull
        @Positive
        @Digits(integer = 12, fraction = 2)
        BigDecimal amount,

        @NotBlank
        String currency,

        @NotNull
        UUID merchantId
) {
}
