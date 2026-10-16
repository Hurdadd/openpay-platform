package com.github.hurdadd.adapter;

import com.github.hurdadd.adapter.out.persistence.idempotency.IdempotencyId;
import com.github.hurdadd.adapter.out.persistence.idempotency.IdempotencyKeyCleaner;
import com.github.hurdadd.adapter.out.persistence.idempotency.JpaIdempotencyEntityRepository;
import com.github.hurdadd.adapter.out.persistence.idempotency.JpaIdempotencyStore;
import com.github.hurdadd.adapter.out.persistence.idempotency.mapper.StoredResponseMapper;
import com.github.hurdadd.application.model.Conflict;
import com.github.hurdadd.application.model.InFlight;
import com.github.hurdadd.application.model.New;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.domain.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** PAY-303: merchant-scoped keys, TTL expiry, in-flight detection, batch cleanup. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // keep MODE=PostgreSQL so ON CONFLICT parses
@Import({JpaIdempotencyStore.class, StoredResponseMapper.class, IdempotencyKeyCleaner.class, IdempotencyStoreTest.Json.class})
@TestPropertySource(properties = {"idempotency.ttl=1h", "idempotency.cleanup.batch-size=2"})
class IdempotencyStoreTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class Json {
        @org.springframework.context.annotation.Bean
        JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
    }

    private static final StoredResponse RESPONSE = new StoredResponse(201, "{}");

    @Autowired JpaIdempotencyStore store;
    @Autowired JpaIdempotencyEntityRepository repository;
    @Autowired IdempotencyKeyCleaner cleaner;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private void expire(MerchantId m) {
        em.createNativeQuery("UPDATE idempotency_keys SET expires_at = ? WHERE merchant_id = ?")
                .setParameter(1, Instant.now().minusSeconds(1)).setParameter(2, m.getValue()).executeUpdate();
    }

    private static MerchantId merchant() { return MerchantId.from(UUID.randomUUID()); }

    @Test
    void same_key_for_two_merchants_is_two_independent_requests() {
        MerchantId a = merchant(), b = merchant();

        assertInstanceOf(New.class, store.tryInsert(a, "k", "h1"));
        assertInstanceOf(New.class, store.tryInsert(b, "k", "h2"));

        store.saveResponse(a, "k", RESPONSE);
        // (not read back as Duplicate: H2 double-encodes the JSON column, Postgres jsonb does not)
        assertNotNull(repository.findById(new IdempotencyId(a.getValue(), "k")).orElseThrow().getResponse());
        assertInstanceOf(InFlight.class, store.tryInsert(b, "k", "h2"));
    }

    /** The claim the resume makes: ten simultaneous requests with one key admit exactly one. */
    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void ten_concurrent_requests_with_the_same_key_admit_exactly_one() throws Exception {
        MerchantId m = merchant();
        int threads = 10;
        var barrier = new java.util.concurrent.CyclicBarrier(threads);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var results = new java.util.ArrayList<java.util.concurrent.Future<Object>>();
        for (int i = 0; i < threads; i++) {
            String hash = i % 2 == 0 ? "body-a" : "body-b"; // half of the callers send a different body
            results.add(pool.submit(() -> {
                barrier.await();
                // each caller is its own transaction, as in production (TX1 of the create flow)
                return new org.springframework.transaction.support.TransactionTemplate(txManager)
                        .execute(status -> store.tryInsert(m, "race", hash));
            }));
        }
        long admitted = 0;
        for (var f : results) {
            Object r = f.get();
            if (r instanceof New) admitted++;
            else org.junit.jupiter.api.Assertions.assertTrue(r instanceof InFlight || r instanceof Conflict,
                    "a loser must see in-flight or conflict, got " + r);
        }
        pool.shutdown();

        assertEquals(1, admitted);
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status ->
                em.createNativeQuery("DELETE FROM idempotency_keys WHERE merchant_id = ?").setParameter(1, m.getValue()).executeUpdate());
    }

    @Test
    void key_without_a_response_is_in_flight_not_an_error() {
        MerchantId m = merchant();
        store.tryInsert(m, "k", "h");

        assertInstanceOf(InFlight.class, store.tryInsert(m, "k", "h"));
    }

    @Test
    void same_key_with_a_different_body_is_a_conflict() {
        MerchantId m = merchant();
        store.tryInsert(m, "k", "h");

        assertInstanceOf(Conflict.class, store.tryInsert(m, "k", "other"));
    }

    @Test
    void expired_key_is_reusable() {
        MerchantId m = merchant();
        store.tryInsert(m, "k", "h");
        store.saveResponse(m, "k", RESPONSE);
        expire(m);

        assertInstanceOf(New.class, store.tryInsert(m, "k", "different"));
    }

    @Test
    void cleaner_deletes_only_expired_rows_in_batches() {
        MerchantId old = merchant(), fresh = merchant();
        for (int i = 0; i < 5; i++) store.tryInsert(old, "k" + i, "h"); // 5 rows, batch size 2 -> 3 rounds
        store.tryInsert(fresh, "k", "h");
        expire(old);

        assertEquals(5, cleaner.purgeExpired());
        assertEquals(1, repository.count());
    }
}
