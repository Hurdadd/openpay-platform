package com.github.hurdadd.application.port.in.results;

import com.github.hurdadd.application.model.StoredResponse;

public record PaymentDuplicate(StoredResponse storedResponse) implements CreatePaymentResult {
}
