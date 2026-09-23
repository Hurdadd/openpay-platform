package com.github.hurdadd.adapter.in.web.payment.dto;

import com.github.hurdadd.domain.PaymentStatus;

import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        PaymentStatus status
) {
}
