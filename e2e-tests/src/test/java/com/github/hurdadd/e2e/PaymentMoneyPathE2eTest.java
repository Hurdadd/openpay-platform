package com.github.hurdadd.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static com.github.hurdadd.e2e.E2eEnvironment.PSP_SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PAY-304. The whole money path through real containers: orchestrator + ledger (built from the
 * shipped bootJars), Postgres, Kafka, Redis, and WireMock standing in for the PSP.
 * Nothing in here touches application classes: HTTP in, SQL/Kafka out.
 */
class PaymentMoneyPathE2eTest {

    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @BeforeEach
    void freshPsp() {
        E2eEnvironment.ORCHESTRATOR.isRunning(); // forces the shared environment to start
        Platform.resetPsp();
    }

    @Test
    void scenario1_pspTimeout_reconcilerSettles_kafkaRedelivery_idempotentReplay() {
        UUID merchant = UUID.randomUUID();
        String key = "K1-" + UUID.randomUUID();

        // 1. create
        Platform.Response first = Platform.createPayment(key, merchant, "100.00");
        assertThat(first.status()).isEqualTo(201);
        UUID paymentId = Platform.paymentId(first);

        // 2. PSP is slower than our time limit -> outcome unknown, parked in PENDING_PSP, authorize sent once
        assertThat(first.body()).contains("PENDING_PSP");
        String pspReference = Platform.pspReference(paymentId);
        // the request is journaled as soon as it arrives, but the call is still in flight on another thread: wait, then exactly one
        await().atMost(PATIENCE).until(() -> Platform.authorizeRequests(pspReference) >= 1);
        assertThat(Platform.authorizeRequests(pspReference)).isEqualTo(1);

        // 3. the PSP did approve; only the reconciler can find out (no callback is ever sent)
        Platform.pspReportsApproved(pspReference);
        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(Platform.status(paymentId)).isEqualTo("SETTLED"));

        // 4. outbox row published to Kafka
        await().atMost(PATIENCE).until(() -> Platform.outboxPublished(paymentId));

        // 5. ledger: one balanced entry, merchant is owed the amount
        await().atMost(PATIENCE).until(() -> Platform.ledgerEntries(paymentId) == 1);
        assertThat(Platform.ledgerSum(paymentId)).isZero();
        assertThat(Platform.merchantBalance(merchant)).isEqualTo(-10_000);

        // 6. same Kafka message again (at-least-once redelivery) -> still one entry
        String message = Platform.readSettledMessage(paymentId);
        Platform.produceAgain(paymentId, message);
        await().atMost(PATIENCE).until(Platform::ledgerConsumerCaughtUp); // the duplicate was really consumed
        assertThat(Platform.ledgerEntries(paymentId)).isEqualTo(1);
        assertThat(Platform.merchantBalance(merchant)).isEqualTo(-10_000);

        // 7. client retries with the same key -> same answer, no second payment
        Platform.Response replay = Platform.createPayment(key, merchant, "100.00");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(Platform.count("openpay", "SELECT count(*) FROM payments WHERE merchant_id = ?", merchant))
                .isEqualTo(1);
    }

    @Test
    void scenario2_signedCallbacks_settleInsteadOfReconciler() {
        UUID merchant = UUID.randomUUID();
        Platform.Response created = Platform.createPayment("K2-" + UUID.randomUUID(), merchant, "25.50");
        assertThat(created.status()).isEqualTo(201);
        UUID paymentId = Platform.paymentId(created);
        String pspReference = Platform.pspReference(paymentId);

        // PSP status stays "unknown" for the whole test, so the reconciler can never be the one that settles it
        assertCallback(Platform.callback(pspReference, "AUTHORIZED", "bad-sig", "wrong-secret"), 401);
        assertCallback(Platform.callback(pspReference, "AUTHORIZED", UUID.randomUUID().toString(), PSP_SECRET), 200);

        String settleNonce = UUID.randomUUID().toString();
        assertCallback(Platform.callback(pspReference, "SETTLED", settleNonce, PSP_SECRET), 200);
        // the PSP retries the very same delivery: acknowledged, but not applied twice
        assertCallback(Platform.callback(pspReference, "SETTLED", settleNonce, PSP_SECRET), 200);

        assertThat(Platform.status(paymentId)).isEqualTo("SETTLED");
        await().atMost(PATIENCE).until(() -> Platform.outboxPublished(paymentId));
        await().atMost(PATIENCE).until(() -> Platform.ledgerEntries(paymentId) == 1);
        assertThat(Platform.ledgerSum(paymentId)).isZero();
        assertThat(Platform.merchantBalance(merchant)).isEqualTo(-2_550);
    }

    private static void assertCallback(Platform.Response response, int expected) {
        assertThat(response.status()).as("callback response: %s", response.body()).isEqualTo(expected);
    }
}
