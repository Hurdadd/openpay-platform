package com.github.hurdadd.application.service;

import com.github.hurdadd.application.model.Conflict;
import com.github.hurdadd.application.model.Duplicate;
import com.github.hurdadd.application.model.IdempotencyResult;
import com.github.hurdadd.application.model.New;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.application.port.in.CreatePaymentCommand;
import com.github.hurdadd.application.port.in.CreatePaymentUseCase;
import com.github.hurdadd.application.port.in.results.CreatePaymentResult;
import com.github.hurdadd.application.port.in.results.PaymentCreated;
import com.github.hurdadd.application.port.in.results.PaymentDuplicate;
import com.github.hurdadd.application.port.in.results.PaymentKeyConflict;
import com.github.hurdadd.application.port.out.IdempotencyStore;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.domain.Money;
import com.github.hurdadd.domain.Payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class CreatePaymentService implements CreatePaymentUseCase {

    private final PaymentRepository repository;
    private final IdempotencyStore idempotencyStore;

    public CreatePaymentService(
            PaymentRepository repository,
            IdempotencyStore idempotencyStore
    ) {
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
    }

    @Override
    public CreatePaymentResult createPayment(CreatePaymentCommand command) {
        String hash = sha256(canonical(command));
        IdempotencyResult result =
                idempotencyStore.tryInsert(command.idempotencyKey(), hash);

        return switch (result) {
            case Duplicate(StoredResponse response) ->
                    new PaymentDuplicate(response);

            case Conflict ignored ->
                    new PaymentKeyConflict();

            case New ignored -> {
                Payment payment = Payment.create(
                        new Money(command.amount(), command.currency()),
                        command.merchantId()
                );
                Payment saved = repository.save(payment);

                String responseBody =
                        "{\"paymentId\":\"" + saved.getPaymentId().getValue()
                                + "\",\"status\":\"" + saved.getPaymentStatus() + "\"}";

                idempotencyStore.saveResponse(
                        command.idempotencyKey(),
                        new StoredResponse(201, responseBody)
                );

                yield new PaymentCreated(saved);
            }
        };
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
