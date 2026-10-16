package com.github.hurdadd.application.port.in;

import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.MerchantId;

import java.math.BigDecimal;

public record CreatePaymentCommand(
        BigDecimal amount,
        Currency currency,
        MerchantId merchantId,
        String idempotencyKey
) {

}
