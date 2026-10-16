package com.github.hurdadd.adapter.out.persistence.idempotency;

import com.github.hurdadd.adapter.out.persistence.idempotency.mapper.StoredResponseMapper;
import com.github.hurdadd.application.model.Conflict;
import com.github.hurdadd.application.model.Duplicate;
import com.github.hurdadd.application.model.IdempotencyResult;
import com.github.hurdadd.application.model.InFlight;
import com.github.hurdadd.application.model.New;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.application.port.out.IdempotencyStore;
import com.github.hurdadd.domain.MerchantId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;

@Repository
public class JpaIdempotencyStore implements IdempotencyStore {

    private final JpaIdempotencyEntityRepository repository;
    private final StoredResponseMapper mapper;
    private final Duration ttl;

    public JpaIdempotencyStore(
            JpaIdempotencyEntityRepository repository,
            StoredResponseMapper mapper,
            @Value("${idempotency.ttl:24h}") Duration ttl
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    @Override
    public IdempotencyResult tryInsert(MerchantId merchantId, String key, String bodyHash) {
        Instant now = Instant.now();
        repository.deleteIfExpired(merchantId.getValue(), key, now);

        int inserted = repository.insertIfAbsent(merchantId.getValue(), key, bodyHash, now, now.plus(ttl));
        if (inserted == 1) {
            return new New();
        }

        IdempotencyEntity existing = repository.findById(new IdempotencyId(merchantId.getValue(), key))
                .orElseThrow(() -> new IdempotencyNotFound(
                        "Idempotency key disappeared after an insert conflict"
                ));

        if (!existing.getBodyHash().equals(bodyHash)) {
            return new Conflict();
        }

        if (existing.getResponse() == null) {
            return new InFlight();
        }

        return new Duplicate(mapper.fromJson(existing.getResponse()));
    }

    @Override
    public void saveResponse(MerchantId merchantId, String key, StoredResponse response) {
        String serialized = mapper.toJson(response);
        int updated = repository.updateResponse(merchantId.getValue(), key, serialized);

        if (updated != 1) {
            throw new IdempotencyNotFound(
                    "Idempotency key not found when trying to save response"
            );
        }
    }
}
