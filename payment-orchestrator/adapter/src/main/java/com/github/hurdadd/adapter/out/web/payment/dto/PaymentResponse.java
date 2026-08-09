package com.github.hurdadd.adapter.out.web.payment.dto;

import com.github.hurdadd.domain.PaymentStatus;

import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        PaymentStatus status
) {
}
