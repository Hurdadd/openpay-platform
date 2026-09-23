package com.github.hurdadd.adapter.out.persistence.idempotency;

import com.github.hurdadd.adapter.out.persistence.idempotency.mapper.StoredResponseMapper;
import com.github.hurdadd.application.model.Conflict;
import com.github.hurdadd.application.model.Duplicate;
import com.github.hurdadd.application.model.IdempotencyResult;
import com.github.hurdadd.application.model.New;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.application.port.out.IdempotencyStore;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public class JpaIdempotencyStore implements IdempotencyStore {

    private final JpaIdempotencyEntityRepository repository;
    private final StoredResponseMapper mapper;

    public JpaIdempotencyStore(
            JpaIdempotencyEntityRepository repository,
            StoredResponseMapper mapper
    ) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public IdempotencyResult tryInsert(String key, String bodyHash) {
        int inserted = repository.insertIfAbsent(key, bodyHash, Instant.now());
        if (inserted == 1) {
            return new New();
        }

        IdempotencyEntity existing = repository.findById(key)
                .orElseThrow(() -> new IdempotencyNotFound(
                        "Idempotency key disappeared after an insert conflict"
                ));

        if (!existing.getBodyHash().equals(bodyHash)) {
            return new Conflict();
        }

        if (existing.getResponse() == null) {
            throw new IdempotencyResponseIsNull(
                    "Idempotency key exists but response is null"
            );
        }

        return new Duplicate(mapper.fromJson(existing.getResponse()));
    }

    @Override
    public void saveResponse(String key, StoredResponse response) {
        String serialized = mapper.toJson(response);
        int updated = repository.updateResponse(key, serialized);

        if (updated != 1) {
            throw new IdempotencyNotFound(
                    "Idempotency key not found when trying to save response"
            );
        }
    }
}
