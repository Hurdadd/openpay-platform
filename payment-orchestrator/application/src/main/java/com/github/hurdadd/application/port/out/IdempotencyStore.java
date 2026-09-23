package com.github.hurdadd.application.port.out;

import com.github.hurdadd.application.model.IdempotencyResult;
import com.github.hurdadd.application.model.StoredResponse;

//for HTTP level idempotency, we need to store the request body hash and the response for a given key (e.g. idempotency key)
public interface IdempotencyStore {

    IdempotencyResult tryInsert(String key, String bodyHash);
    void saveResponse(String key, StoredResponse response);

}
