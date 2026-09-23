package com.github.hurdadd.adapter.in.web.payment;

import com.github.hurdadd.adapter.in.web.payment.dto.CreatePaymentRequest;
import com.github.hurdadd.adapter.in.web.payment.dto.PaymentResponse;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.application.port.in.CreatePaymentCommand;
import com.github.hurdadd.application.port.in.CreatePaymentUseCase;
import com.github.hurdadd.application.port.in.results.PaymentCreated;
import com.github.hurdadd.application.port.in.results.PaymentDuplicate;
import com.github.hurdadd.application.port.in.results.PaymentKeyConflict;
import com.github.hurdadd.domain.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final CreatePaymentUseCase createPaymentUseCase;

    public PaymentController(CreatePaymentUseCase createPaymentUseCase) {
        this.createPaymentUseCase = createPaymentUseCase;
    }

    @PostMapping
    public ResponseEntity<?> createPayment(
            @RequestHeader("idempotency-key") String idempotencyKey,
            @RequestBody CreatePaymentRequest body
    ) {
        CreatePaymentCommand command = new CreatePaymentCommand(
                body.amount(),
                body.currency(),
                body.merchantId(),
                idempotencyKey
        );

        return switch (createPaymentUseCase.createPayment(command)) {
            case PaymentCreated created -> {
                Payment payment = created.payment();
                PaymentResponse response = new PaymentResponse(
                        payment.getPaymentId().getValue(),
                        payment.getPaymentStatus()
                );
                yield ResponseEntity.status(HttpStatus.CREATED).body(response);
            }

            case PaymentDuplicate duplicate ->
                    replayStoredResponse(duplicate.storedResponse());

            case PaymentKeyConflict ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }

    private ResponseEntity<String> replayStoredResponse(StoredResponse stored) {
        return ResponseEntity
                .status(stored.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(stored.body());
    }
}
