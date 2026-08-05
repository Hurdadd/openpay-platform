package com.github.hurdadd.payment.port.out;

import com.github.hurdadd.payment.Payment;
import com.github.hurdadd.payment.model.IdempotencyResult;
import com.github.hurdadd.payment.model.StoredResponse;

public interface IdempotencyStore {

    IdempotencyResult tryInsert(String key, String bodyHash);
    Payment saveResponse(String key, StoredResponse response);

}
