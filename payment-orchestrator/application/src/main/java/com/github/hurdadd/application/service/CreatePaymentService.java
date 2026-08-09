package com.github.hurdadd.application.service;

import com.github.hurdadd.domain.Money;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.application.model.*;
import com.github.hurdadd.application.port.in.CreatePaymentCommand;
import com.github.hurdadd.application.port.in.CreatePaymentUseCase;
import com.github.hurdadd.application.port.in.results.CreatePaymentResult;
import com.github.hurdadd.application.port.in.results.PaymentCreated;
import com.github.hurdadd.application.port.in.results.PaymentDuplicate;
import com.github.hurdadd.application.port.in.results.PaymentKeyConflict;
import com.github.hurdadd.application.port.out.IdempotencyStore;
import com.github.hurdadd.application.port.out.PaymentRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


public class CreatePaymentService implements CreatePaymentUseCase {

    private final PaymentRepository repository;
    private final IdempotencyStore idempotencyStore;

    public CreatePaymentService(PaymentRepository repository, IdempotencyStore idempotencyStore) {
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
    }

    @Override
    public CreatePaymentResult createPayment(CreatePaymentCommand command) {

        String hash = sha256(canonical(command));
        IdempotencyResult idempotencyResultSaved = idempotencyStore.tryInsert(command.idempotencyKey(), hash);

        switch (idempotencyResultSaved){
            case Duplicate(StoredResponse response) -> {
                return new PaymentDuplicate(response);
            }
            case Conflict() -> {
                return new PaymentKeyConflict();
            }
            case New() -> {
                Payment payment = Payment.create(money(command) , command.merchantId());
                Payment saved = repository.save(payment);
                String responseBody = "{\"paymentId\":\"" + saved.getPaymentId().getValue()
                        + "\",\"status\":\"" + saved.getPaymentStatus() + "\"}";
                idempotencyStore.saveResponse(command.idempotencyKey(), new StoredResponse(201, responseBody));
                return new PaymentCreated(saved);
            }
            default -> throw new IllegalStateException("Unexpected value: " + idempotencyResultSaved);
        }

    }

    ///////////helper////////////
    private Money money(CreatePaymentCommand command){
        return new Money(command.amount() , command.currency());
    }

    private String sha256(String input){
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);}
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
    private String canonical(CreatePaymentCommand c) {
        return "amount=" + c.amount()
                + "&currency=" + c.currency()
                + "&merchantId=" + c.merchantId().getValue();
    }
}
