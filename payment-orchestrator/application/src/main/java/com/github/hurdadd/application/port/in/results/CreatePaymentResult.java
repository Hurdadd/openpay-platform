package com.github.hurdadd.application.port.in.results;

public sealed interface CreatePaymentResult permits PaymentCreated, PaymentDuplicate, PaymentKeyConflict, PaymentInFlight {

}
