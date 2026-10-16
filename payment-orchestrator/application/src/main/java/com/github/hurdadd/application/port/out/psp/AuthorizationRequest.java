package com.github.hurdadd.application.port.out.psp;

import com.github.hurdadd.domain.MerchantReference;
import com.github.hurdadd.domain.Money;
import com.github.hurdadd.domain.PspReference;


public record AuthorizationRequest(
        Money money,
        MerchantReference reference,
        PspReference pspReference
) {
}
