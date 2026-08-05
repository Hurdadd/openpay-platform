package com.github.hurdadd.payment.persistence.idempotency;

import com.github.hurdadd.payment.Payment;
import com.github.hurdadd.payment.model.*;
import com.github.hurdadd.payment.persistence.idempotency.mapper.StoredResponseMapper;
import com.github.hurdadd.payment.port.out.IdempotencyStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;



@Repository
public class JpaIdempotencyStore implements IdempotencyStore {
    private final JpaIdempotencyEntityRepository repository;
    private final StoredResponseMapper mapper;

    public JpaIdempotencyStore(JpaIdempotencyEntityRepository repository, StoredResponseMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public IdempotencyResult tryInsert(String key, String bodyHash) {
        try {
            IdempotencyEntity savedIdempotency = IdempotencyEntity.builder()
                    .bodyHash(bodyHash)
                    .response(null)
                    .idempotencyKey(key)
                    .createdAt(Instant.now())
                    .build()
            ;
            repository.save(savedIdempotency);
            return new New();
        }catch (DataIntegrityViolationException e){
            IdempotencyEntity existing = repository.findById(key).orElseThrow(
               () -> new IdempotencyNotFound("Idempotency key not found after DataIntegrityViolationException"));

            if (!existing.getBodyHash().equals(bodyHash)) {
                return new Conflict();
            }
            if (existing.getResponse() == null)  {
                throw new IdempotencyResponseIsNull("Idempotency key exists but response is null, this should not happen");
            }

            return new Duplicate(mapper.fromJson(existing.getResponse()));


            }

    }

    @Override
    public Payment saveResponse(String key, StoredResponse response) {
        IdempotencyEntity entity = repository.findById(key).orElseThrow(
                () -> new IdempotencyNotFound("Idempotency key not found when trying to save response")
        );
        entity.setResponse(mapper.toJson(response));
        repository.save(entity);
    }
}
