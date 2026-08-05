package com.github.hurdadd.payment.persistence.web.dto;

import com.github.hurdadd.payment.PaymentStatus;

import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        PaymentStatus status
) {
}
