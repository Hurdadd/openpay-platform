package com.github.hurdadd.payment.persistence.web.dto;

import com.github.hurdadd.payment.Currency;
import com.github.hurdadd.payment.MerchantId;

import java.math.BigDecimal;


public record CreatePaymentRequest(
        BigDecimal amount,
        Currency currency,
        MerchantId merchantId

) {
}
