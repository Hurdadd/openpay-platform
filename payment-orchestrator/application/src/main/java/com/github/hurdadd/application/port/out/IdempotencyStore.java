package com.github.hurdadd.application.port.out;

import com.github.hurdadd.application.model.IdempotencyResult;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.domain.MerchantId;

//for HTTP level idempotency, we need to store the request body hash and the response for a given key (e.g. idempotency key)
// keys are scoped per merchant: the same key from two merchants is two unrelated requests
public interface IdempotencyStore {

    IdempotencyResult tryInsert(MerchantId merchantId, String key, String bodyHash);
    void saveResponse(MerchantId merchantId, String key, StoredResponse response);

}
