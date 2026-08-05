package com.github.hurdadd.payment.port.in;

import com.github.hurdadd.payment.Currency;
import com.github.hurdadd.payment.MerchantId;

import java.math.BigDecimal;

public record CreatePaymentCommand(
        BigDecimal amount,
        Currency currency,
        MerchantId merchantId,
        String idempotencyKey
) {
}
