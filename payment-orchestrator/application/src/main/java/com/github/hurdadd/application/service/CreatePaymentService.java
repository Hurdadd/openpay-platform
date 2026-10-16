package com.github.hurdadd.application.service;

import com.github.hurdadd.application.model.Conflict;
import com.github.hurdadd.application.model.Duplicate;
import com.github.hurdadd.application.model.IdempotencyResult;
import com.github.hurdadd.application.model.InFlight;
import com.github.hurdadd.application.model.New;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.application.port.in.CreatePaymentCommand;
import com.github.hurdadd.application.port.in.CreatePaymentUseCase;
import com.github.hurdadd.application.port.in.results.CreatePaymentResult;
import com.github.hurdadd.application.port.in.results.PaymentCreated;
import com.github.hurdadd.application.port.in.results.PaymentDuplicate;
import com.github.hurdadd.application.port.in.results.PaymentInFlight;
import com.github.hurdadd.application.port.in.results.PaymentKeyConflict;
import com.github.hurdadd.application.port.out.IdempotencyStore;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.port.out.TxRunner;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspOutcome;
import com.github.hurdadd.domain.Money;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.MerchantReference;
import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.domain.PspReference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class CreatePaymentService implements CreatePaymentUseCase {

    private static final System.Logger LOG = System.getLogger(CreatePaymentService.class.getName());

    private final PaymentRepository repository;
    private final IdempotencyStore idempotencyStore;
    private final PspClient pspClient;
    private final TxRunner tx;

    public CreatePaymentService(
            PaymentRepository repository,
            IdempotencyStore idempotencyStore,
            PspClient pspClient,
            TxRunner tx
    ) {
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
        this.pspClient = pspClient;
        this.tx = tx;
    }

    // either an early answer (duplicate / conflict) or the freshly committed CREATED payment
    private record Admission(CreatePaymentResult early, Payment payment) {}

    @Override
    public CreatePaymentResult createPayment(CreatePaymentCommand command) {
        String hash = sha256(canonical(command));

        // TX1: idempotency row + payment(CREATED, pspReference already minted), committed together
        Admission admission = tx.inTx(() -> admit(command, hash));
        if (admission.early() != null) {
            return admission.early();
        }
        Payment created = admission.payment();

        // outside any transaction: a slow PSP must not hold a DB connection
        PspOutcome outcome = authorize(created);

        // TX2: record what we learned
        Payment settled = tx.inTx(() -> record(created, outcome, command));
        return new PaymentCreated(settled);
    }

    private Admission admit(CreatePaymentCommand command, String hash) {
        return switch (idempotencyStore.tryInsert(command.merchantId(), command.idempotencyKey(), hash)) {
            case Duplicate(StoredResponse response) -> new Admission(new PaymentDuplicate(response), null);
            case Conflict ignored -> new Admission(new PaymentKeyConflict(), null);
            case InFlight ignored -> new Admission(new PaymentInFlight(), null);
            case New ignored -> new Admission(null, repository.save(Payment.create(
                    new Money(command.amount(), command.currency()),
                    command.merchantId(),
                    PspReference.generate())));
        };
    }

    // one attempt, no retry: if it fails we do NOT know whether the PSP charged, so it is reported
    // as TIMEOUT (unknown) and the reconciler resolves it later using the same reference
    private PspOutcome authorize(Payment payment) {
        try {
            return pspClient.authorize(new AuthorizationRequest(
                    payment.getMoney(),
                    MerchantReference.of(payment.getMerchantId().getValue().toString()),
                    payment.getPspReference())).pspOutcome();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "PSP authorize outcome unknown for " + payment.getPspReference().value(), e);
            return PspOutcome.TIMEOUT;
        }
    }

    private Payment record(Payment created, PspOutcome outcome, CreatePaymentCommand command) {
        // re-read: a callback or the reconciler may have moved it while we were talking to the PSP
        Payment payment = repository.findById(created.getPaymentId()).orElseThrow();
        if (payment.getPaymentStatus() == PaymentStatus.CREATED) {
            switch (outcome) {
                case APPROVED -> {
                    payment.markPending();
                    payment.authorize();
                }
                case DECLINED -> payment.fail();
                // a PSP cannot reverse something it has just been asked to authorize; treat it as unknown
                // and let the reconciler ask again with the same reference
                case TIMEOUT, REVERSED -> payment.markPending();
            }
            payment = repository.save(payment);
        }

        String responseBody =
                "{\"paymentId\":\"" + payment.getPaymentId().getValue()
                        + "\",\"status\":\"" + payment.getPaymentStatus() + "\"}";
        idempotencyStore.saveResponse(command.merchantId(), command.idempotencyKey(), new StoredResponse(201, responseBody));
        return payment;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String canonical(CreatePaymentCommand command) {
        return "amount=" + command.amount().stripTrailingZeros().toPlainString()
                + "&currency=" + command.currency()
                + "&merchantId=" + command.merchantId().getValue();
    }
}
