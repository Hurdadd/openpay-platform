package com.github.hurdadd.adapter;

import com.github.hurdadd.application.model.IdempotencyResult;
import com.github.hurdadd.application.model.New;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.application.port.in.CreatePaymentCommand;
import com.github.hurdadd.application.port.in.results.PaymentCreated;
import com.github.hurdadd.application.port.out.IdempotencyStore;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.port.out.TxRunner;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspOutcome;
import com.github.hurdadd.application.port.out.psp.PspResult;
import com.github.hurdadd.application.service.CreatePaymentService;
import com.github.hurdadd.application.service.PaymentReconciler;
import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.MerchantId;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentId;
import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.domain.PspReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PAY-203: TX1 -> PSP (no tx) -> TX2, with real Spring transaction synchronization and in-memory ports. */
class CreatePaymentServiceTest {

    /** No database: just enough of a transaction manager for isActualTransactionActive() to be truthful. */
    static class NoopTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition def) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    static class Repo implements PaymentRepository {
        final Map<UUID, Payment> byId = new HashMap<>();
        public Payment save(Payment p) { byId.put(p.getPaymentId().getValue(), p); return p; }
        public Optional<Payment> findById(PaymentId id) { return Optional.ofNullable(byId.get(id.getValue())); }
        public Optional<Payment> findByPspReference(PspReference r) { throw new UnsupportedOperationException(); }
        public List<Payment> findStuckPending(Instant olderThan, Instant newerThan) {
            return byId.values().stream()
                    .filter(p -> p.getPspReference() != null)
                    .filter(p -> p.getPaymentStatus() == PaymentStatus.CREATED
                            || p.getPaymentStatus() == PaymentStatus.PENDING_PSP)
                    .toList();
        }
    }

    static class Idem implements IdempotencyStore {
        StoredResponse saved;
        public IdempotencyResult tryInsert(com.github.hurdadd.domain.MerchantId m, String key, String bodyHash) { return new New(); }
        public void saveResponse(com.github.hurdadd.domain.MerchantId m, String key, StoredResponse r) { saved = r; }
    }

    static class Psp implements PspClient {
        boolean sawActiveTx;
        PspReference sentReference;
        PspOutcome outcome = PspOutcome.APPROVED;
        boolean timesOut;
        public PspResult authorize(AuthorizationRequest r) {
            sawActiveTx = TransactionSynchronizationManager.isActualTransactionActive();
            sentReference = r.pspReference();
            if (timesOut) throw new RuntimeException("timeout");
            return new PspResult(outcome, r.pspReference());
        }
        public PspResult checkStatus(PspReference ref) { return new PspResult(PspOutcome.APPROVED, ref); }
    }

    private final Repo repo = new Repo();
    private final Idem idem = new Idem();
    private final Psp psp = new Psp();
    private final TransactionTemplate template = new TransactionTemplate(new NoopTxManager());
    private int txCalls;
    private int failOnCall = -1;

    private final TxRunner tx = new TxRunner() {
        @Override
        public <T> T inTx(Supplier<T> work) {
            if (++txCalls == failOnCall) throw new IllegalStateException("crash before this tx");
            return template.execute(s -> work.get());
        }
    };

    private final CreatePaymentService service = new CreatePaymentService(repo, idem, psp, tx);

    private final CreatePaymentCommand command = new CreatePaymentCommand(
            new BigDecimal("10.00"), Currency.USD, MerchantId.from(UUID.randomUUID()), "key-1");

    private Payment only() { return repo.byId.values().iterator().next(); }

    @Test
    void psp_is_called_outside_a_transaction_with_the_stored_reference() {
        service.createPayment(command);

        assertFalse(psp.sawActiveTx);
        assertEquals(only().getPspReference(), psp.sentReference);
    }

    @Test
    void approved_becomes_authorized_and_response_matches() {
        PaymentCreated created = (PaymentCreated) service.createPayment(command);

        assertEquals(PaymentStatus.AUTHORIZED, created.payment().getPaymentStatus());
        assertTrue(idem.saved.body().contains("AUTHORIZED"));
    }

    @Test
    void declined_becomes_failed() {
        psp.outcome = PspOutcome.DECLINED;
        service.createPayment(command);
        assertEquals(PaymentStatus.FAILED, only().getPaymentStatus());
    }

    @Test
    void timeout_leaves_payment_pending_not_failed() {
        psp.timesOut = true;
        service.createPayment(command);

        assertEquals(PaymentStatus.PENDING_PSP, only().getPaymentStatus());
        assertTrue(idem.saved.body().contains("PENDING_PSP"));
    }

    @Test
    void crash_between_tx1_and_tx2_is_picked_up_by_the_reconciler() {
        failOnCall = 2; // TX2 never happens
        assertThrows(IllegalStateException.class, () -> service.createPayment(command));
        assertEquals(PaymentStatus.CREATED, only().getPaymentStatus());
        assertTrue(only().getPspReference() != null);

        Clock later = Clock.fixed(Instant.now().plusSeconds(600), ZoneOffset.UTC);
        new PaymentReconciler(repo, psp, later, Duration.ZERO, Duration.ofDays(1), tx).reconcile();

        assertEquals(PaymentStatus.AUTHORIZED, only().getPaymentStatus());
    }
}
