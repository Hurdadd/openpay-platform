package com.github.hurdadd.adapter.out.persistence.idempotency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/** Deletes expired idempotency keys in small batches so one run never holds a long lock. */
@Service
public class IdempotencyKeyCleaner {

    private final JpaIdempotencyEntityRepository repository;
    private final TransactionTemplate tx;
    private final int batchSize;

    public IdempotencyKeyCleaner(
            JpaIdempotencyEntityRepository repository,
            TransactionTemplate tx,
            @Value("${idempotency.cleanup.batch-size:500}") int batchSize
    ) {
        this.repository = repository;
        this.tx = tx;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idempotency.cleanup.interval:1h}")
    public int purgeExpired() {
        int total = 0;
        int deleted;
        do {
            deleted = tx.execute(s -> repository.deleteExpiredBatch(Instant.now(), batchSize));
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
