package com.github.hurdadd.payment.port.in.results;

import com.github.hurdadd.payment.model.StoredResponse;

public record PaymentDuplicate(StoredResponse storedResponse) implements CreatePaymentResult {
}
