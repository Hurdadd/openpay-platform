package com.github.hurdadd.application.port.in;

import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.domain.PspReference;

public interface PspCallbackHandler {

    void handle(PspReference reference, PaymentStatus status);
}
