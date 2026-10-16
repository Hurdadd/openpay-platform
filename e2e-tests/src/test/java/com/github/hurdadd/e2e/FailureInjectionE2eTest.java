package com.github.hurdadd.e2e;

import com.github.dockerjava.api.DockerClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static com.github.hurdadd.e2e.E2eEnvironment.PSP_SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PAY-305. The PAY-304 platform again, but with Toxiproxy between the orchestrator and Postgres,
 * Redis and Kafka, WireMock scenarios for PSP/callback misbehaviour, and a hard kill of the service.
 * The invariant under every fault: no lost or duplicated ledger entry, one PSP charge per payment.
 */
class FailureInjectionE2eTest {

    private static final Duration PATIENCE = Duration.ofSeconds(90);

    @BeforeEach
    void cleanSlate() throws Exception {
        E2eEnvironment.ORCHESTRATOR.isRunning(); // forces the shared environment to start
        healAll();
        Platform.resetPsp();
    }

    @AfterEach
    void heal() throws Exception {
        healAll();
    }

    private static void healAll() throws Exception {
        for (var proxy : List.of(E2eEnvironment.pgProxy, E2eEnvironment.redisProxy, E2eEnvironment.kafkaProxy)) {
            proxy.enable();
            for (var toxic : proxy.toxics().getAll()) {
                toxic.remove();
            }
        }
    }

    // ---- Postgres latency ----------------------------------------------------------------

    @Test
    void postgresLatency_apiAnswersWithinBound_poolDrains_recoversAfterHeal() throws Exception {
        E2eEnvironment.pgProxy.toxics().latency("slow-db", ToxicDirection.DOWNSTREAM, 2_000);

        int clients = 24; // more than the Hikari pool (10), so a leak or a stuck pool would show
        UUID merchant = UUID.randomUUID();
        List<String> keys = new ArrayList<>();
        List<Future<Platform.Response>> calls = new ArrayList<>();
        long started = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                String key = "LAT-" + UUID.randomUUID();
                keys.add(key);
                calls.add(pool.submit(() -> Platform.createPaymentWithin(Duration.ofSeconds(20), key, merchant, "10.00")));
            }
            List<Platform.Response> answers = new ArrayList<>();
            for (var call : calls) {
                answers.add(call.get());
            }
            Duration slowest = Duration.ofNanos(System.nanoTime() - started);

            // the client always got an answer (a definite error is fine, a hang is not) ...
            assertThat(answers).as("answers: %s", answers).noneMatch(r -> r.status() == -1);
            // ... and a failure is a server error, never a bogus success body
            assertThat(answers).allMatch(r -> r.status() == 201 || r.status() == 503);
            assertThat(slowest).as("slowest answer under 2s DB latency").isLessThan(Duration.ofSeconds(10));
        }

        E2eEnvironment.pgProxy.toxics().get("slow-db").remove();

        // nothing is left holding or waiting for a connection once the database is healthy again
        await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(Platform.hikari("active")).isZero();
            assertThat(Platform.hikari("pending")).isZero();
        });
        Platform.Response after = Platform.createPaymentWithin(Duration.ofSeconds(3), "LAT-AFTER-" + UUID.randomUUID(),
                merchant, "10.00");
        assertThat(after.status()).as(after.body()).isEqualTo(201);

        // retrying the same keys after the fault never creates a second payment for a key:
        // 201 (replayed or created now) or 409 (first attempt's key row survived, still "in progress")
        for (String key : keys) {
            Platform.Response retry = Platform.createPaymentWithin(Duration.ofSeconds(5), key, merchant, "10.00");
            assertThat(retry.status()).as("retry of %s: %s", key, retry.body()).isIn(201, 409);
        }
        assertThat(Platform.count("openpay", "SELECT count(*) FROM payments WHERE merchant_id = ?", merchant))
                .isLessThanOrEqualTo(clients + 1);
    }

    // ---- Kafka outage --------------------------------------------------------------------

    @Test
    void kafkaDown30s_outboxAccumulates_apiKeepsWorking_thenEverythingPublishedInOrder() throws Exception {
        UUID merchant = UUID.randomUUID();
        E2eEnvironment.kafkaProxy.disable();

        List<UUID> payments = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            payments.add(Platform.settledViaCallbacks(merchant, "10.00")); // API fully works without Kafka
        }
        Thread.sleep(Duration.ofSeconds(30));

        for (UUID id : payments) {
            assertThat(Platform.status(id)).isEqualTo("SETTLED");
            assertThat(Platform.unpublishedOutbox(id)).as("outbox of %s while Kafka is down", id).isEqualTo(1);
            assertThat(Platform.ledgerEntries(id)).isZero();
        }
        // still serving while the outbox is backed up
        assertThat(Platform.createPayment("KD-" + UUID.randomUUID(), merchant, "1.00").status()).isEqualTo(201);

        E2eEnvironment.kafkaProxy.enable();

        await().atMost(PATIENCE).untilAsserted(() ->
                payments.forEach(id -> assertThat(Platform.outboxPublished(id)).isTrue()));
        await().atMost(PATIENCE).untilAsserted(() ->
                payments.forEach(id -> assertThat(Platform.ledgerEntries(id)).isEqualTo(1)));

        assertExactlyOncePerPaymentInOrder(payments);
        assertThat(Platform.merchantBalance(merchant)).isEqualTo(-5_000);
    }

    /** One message per payment key, and within each partition in the order the outbox rows were written. */
    private static void assertExactlyOncePerPaymentInOrder(List<UUID> payments) {
        List<Platform.Delivered> delivered = Platform.delivered(payments);
        Map<String, Long> perKey = delivered.stream()
                .collect(Collectors.groupingBy(Platform.Delivered::key, Collectors.counting()));
        assertThat(perKey).hasSize(payments.size()).allSatisfy((key, n) -> assertThat(n).isEqualTo(1));

        List<String> written = Platform.outboxOrder(payments);
        Map<Integer, List<String>> byPartition = delivered.stream().collect(Collectors.groupingBy(
                Platform.Delivered::partition, Collectors.mapping(Platform.Delivered::key, Collectors.toList())));
        byPartition.values().forEach(keysInPartition ->
                assertThat(keysInPartition).isEqualTo(written.stream().filter(keysInPartition::contains).toList()));
    }

    // ---- Redis outage --------------------------------------------------------------------

    @Test
    void redisDown_failsOpenPerAdr0013_paymentsAndCallbacksStillSettle_noDuplicateEntry() throws Exception {
        E2eEnvironment.redisProxy.disable();
        UUID merchant = UUID.randomUUID();

        // payments never depended on Redis (idempotency lives in Postgres, ADR-0014)
        Platform.Response created = Platform.createPayment("RD-" + UUID.randomUUID(), merchant, "40.00");
        assertThat(created.status()).isEqualTo(201);
        UUID id = Platform.paymentId(created);
        String ref = Platform.pspReference(id);

        // callbacks are accepted without the nonce check (fail-open), the state machine keeps them safe
        assertThat(Platform.callback(ref, "AUTHORIZED", UUID.randomUUID().toString(), PSP_SECRET).status()).isEqualTo(200);
        String nonce = UUID.randomUUID().toString();
        assertThat(Platform.callback(ref, "SETTLED", nonce, PSP_SECRET).status()).isEqualTo(200);
        // ... including a literal replay, which the missing nonce store can no longer reject
        assertThat(Platform.callback(ref, "SETTLED", nonce, PSP_SECRET).status()).isEqualTo(200);
        // forged callbacks are still rejected: authenticity is HMAC, not Redis
        assertThat(Platform.callback(ref, "SETTLED", UUID.randomUUID().toString(), "wrong-secret").status()).isEqualTo(401);

        await().atMost(PATIENCE).until(() -> Platform.ledgerEntries(id) == 1);
        E2eEnvironment.redisProxy.enable();
        assertThat(Platform.callback(ref, "SETTLED", UUID.randomUUID().toString(), PSP_SECRET).status()).isEqualTo(200);
        assertThat(Platform.ledgerEntries(id)).isEqualTo(1);
        assertThat(Platform.merchantBalance(merchant)).isEqualTo(-4_000);
    }

    // ---- callbacks: duplicate / out of order / missing -----------------------------------

    @Test
    void duplicateCallbacks_sentConcurrently_settleOnce() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID id = Platform.paymentId(Platform.createPayment("DUP-" + UUID.randomUUID(), merchant, "15.00"));
        String ref = Platform.pspReference(id);
        assertThat(Platform.callback(ref, "AUTHORIZED", UUID.randomUUID().toString(), PSP_SECRET).status()).isEqualTo(200);

        // the PSP delivers the same SETTLED five times at once, each with its own nonce
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            List<Future<Platform.Response>> sent = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                sent.add(pool.submit(() -> Platform.callback(ref, "SETTLED", UUID.randomUUID().toString(), PSP_SECRET)));
            }
            for (var s : sent) {
                // 200 (applied or no-op); a lost optimistic-lock race may ask the PSP to retry (409)
                assertThat(s.get().status()).isIn(200, 409);
            }
        }
        assertThat(Platform.callback(ref, "SETTLED", UUID.randomUUID().toString(), PSP_SECRET).status()).isEqualTo(200);

        await().atMost(PATIENCE).until(() -> Platform.ledgerEntries(id) == 1);
        await().atMost(PATIENCE).until(() -> Platform.outboxPublished(id));
        assertThat(Platform.count("openpay", "SELECT count(*) FROM payment_outbox WHERE payment_id = ?", id)).isEqualTo(1);
        assertThat(Platform.merchantBalance(merchant)).isEqualTo(-1_500);
        assertSingleCharge(ref);
    }

    @Test
    void outOfOrderCallbacks_earlySettledIsDeferred_thenOrderRestores() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID id = Platform.paymentId(Platform.createPayment("OOO-" + UUID.randomUUID(), merchant, "20.00"));
        String ref = Platform.pspReference(id);
        String settleNonce = UUID.randomUUID().toString();

        // SETTLED overtakes AUTHORIZED: not applied, PSP is told to retry (409), nothing is lost
        assertThat(Platform.callback(ref, "SETTLED", settleNonce, PSP_SECRET).status()).isEqualTo(409);
        assertThat(Platform.status(id)).isEqualTo("PENDING_PSP");
        assertThat(Platform.ledgerEntries(id)).isZero();

        assertThat(Platform.callback(ref, "AUTHORIZED", UUID.randomUUID().toString(), PSP_SECRET).status()).isEqualTo(200);
        // the PSP retries the very same delivery: the nonce was released on 409, so it is accepted now
        assertThat(Platform.callback(ref, "SETTLED", settleNonce, PSP_SECRET).status()).isEqualTo(200);
        // a stale AUTHORIZED arriving after SETTLED is acknowledged and ignored
        assertThat(Platform.callback(ref, "AUTHORIZED", UUID.randomUUID().toString(), PSP_SECRET).status()).isEqualTo(200);

        assertThat(Platform.status(id)).isEqualTo("SETTLED");
        await().atMost(PATIENCE).until(() -> Platform.ledgerEntries(id) == 1);
        assertThat(Platform.merchantBalance(merchant)).isEqualTo(-2_000);
        assertSingleCharge(ref);
    }

    @Test
    void missingCallback_pspCatchesUpAfterSeveralPolls_reconcilerSettlesWithSingleCharge() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID id = Platform.paymentId(Platform.createPayment("MISS-" + UUID.randomUUID(), merchant, "30.00"));
        String ref = Platform.pspReference(id);

        // WireMock scenario: "unknown" twice, then APPROVED. No callback is ever sent.
        Platform.pspApprovesAfterPolls(ref, 2);

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(Platform.status(id)).isEqualTo("SETTLED"));
        assertThat(Platform.statusRequests(ref)).isGreaterThanOrEqualTo(3);
        await().atMost(PATIENCE).until(() -> Platform.ledgerEntries(id) == 1);
        assertThat(Platform.merchantBalance(merchant)).isEqualTo(-3_000);
        assertSingleCharge(ref);
    }

    /**
     * WireMock journals a request only when its 3s-delayed response completes, so wait that out first.
     * "At most": an open PSP circuit breaker (earlier scenarios time the PSP out on purpose) sends none.
     */
    private static void assertSingleCharge(String pspReference) throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(4));
        assertThat(Platform.authorizeRequests(pspReference)).as("PSP authorize calls").isLessThanOrEqualTo(1);
    }

    // ---- crash mid-publish ---------------------------------------------------------------

    @Test
    void serviceKilledWhilePublishing_afterRestartNothingIsLost() throws Exception {
        UUID merchant = UUID.randomUUID();
        E2eEnvironment.kafkaProxy.disable(); // the publisher is now stuck inside send(), inside its DB transaction

        List<UUID> payments = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            payments.add(Platform.settledViaCallbacks(merchant, "10.00"));
        }
        Thread.sleep(Duration.ofSeconds(7)); // > one scheduler tick (5s): a publish attempt is definitely in flight

        DockerClient docker = E2eEnvironment.ORCHESTRATOR.getDockerClient();
        String container = E2eEnvironment.ORCHESTRATOR.getContainerId();
        docker.killContainerCmd(container).exec(); // SIGKILL: no graceful shutdown, no rollback hooks
        E2eEnvironment.kafkaProxy.enable();
        payments.forEach(id -> assertThat(Platform.status(id)).isEqualTo("SETTLED"));
        payments.forEach(id -> assertThat(Platform.unpublishedOutbox(id)).isEqualTo(1)); // survived on disk

        docker.startContainerCmd(container).exec();
        await().atMost(PATIENCE).until(Platform::orchestratorHealthy);

        await().atMost(PATIENCE).untilAsserted(() ->
                payments.forEach(id -> assertThat(Platform.outboxPublished(id)).isTrue()));
        await().atMost(PATIENCE).untilAsserted(() ->
                payments.forEach(id -> assertThat(Platform.ledgerEntries(id)).isEqualTo(1)));
        payments.forEach(id -> assertThat(Platform.ledgerSum(id)).isZero());
        assertThat(Platform.merchantBalance(merchant)).isEqualTo(-3_000);
        // at-least-once: a message may be on the topic twice, but never zero times
        assertThat(Platform.delivered(payments).stream().map(Platform.Delivered::key).distinct().count())
                .isEqualTo(payments.size());
    }
}
