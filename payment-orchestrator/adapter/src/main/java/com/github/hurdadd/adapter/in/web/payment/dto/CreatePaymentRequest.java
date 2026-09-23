package com.github.hurdadd.adapter.in.web.payment.dto;

import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.MerchantId;

import java.math.BigDecimal;


public record CreatePaymentRequest(
        BigDecimal amount,
        Currency currency,
        MerchantId merchantId

) {
}
