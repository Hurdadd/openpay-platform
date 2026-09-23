package com.github.hurdadd.application.port.out.psp;

import com.github.hurdadd.domain.MerchantReference;
import com.github.hurdadd.domain.Money;


public record AuthorizationRequest(
        Money money,
        MerchantReference reference
) {
}
