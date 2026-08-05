package com.github.hurdadd.payment.port.in.results;

public sealed interface CreatePaymentResult permits PaymentCreated, PaymentDuplicate, PaymentKeyConflict {

}
