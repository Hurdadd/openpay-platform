package com.github.hurdadd.application.port.out;

import com.github.hurdadd.application.model.IdempotencyResult;
import com.github.hurdadd.application.model.StoredResponse;

public interface IdempotencyStore {

    IdempotencyResult tryInsert(String key, String bodyHash);
    void saveResponse(String key, StoredResponse response);

}
