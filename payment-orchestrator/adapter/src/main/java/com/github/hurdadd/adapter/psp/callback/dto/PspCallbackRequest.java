package com.github.hurdadd.adapter.psp.callback.dto;

import com.github.hurdadd.domain.PaymentStatus;

public record PspCallbackRequest(
        String pspReference,
        PaymentStatus status
) {
}
