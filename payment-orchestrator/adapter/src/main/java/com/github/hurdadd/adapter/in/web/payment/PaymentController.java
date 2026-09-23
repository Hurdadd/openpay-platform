package com.github.hurdadd.adapter.in.web.payment;


import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.adapter.in.web.payment.dto.CreatePaymentRequest;
import com.github.hurdadd.adapter.in.web.payment.dto.PaymentResponse;
import com.github.hurdadd.application.port.in.CreatePaymentCommand;
import com.github.hurdadd.application.port.in.CreatePaymentUseCase;
import com.github.hurdadd.application.port.in.results.CreatePaymentResult;
import com.github.hurdadd.application.port.in.results.PaymentCreated;
import com.github.hurdadd.application.port.in.results.PaymentDuplicate;
import com.github.hurdadd.application.port.in.results.PaymentKeyConflict;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        CreatePaymentResult result = createPaymentUseCase.createPayment(command);
        return switch (result) {
            case PaymentCreated created -> {
                Payment payment = created.payment();
                PaymentResponse response = new PaymentResponse(
                        payment.getPaymentId().getValue(),
                        payment.getPaymentStatus()
                );
                yield ResponseEntity.status(HttpStatus.CREATED).body(response);
            }

            case PaymentDuplicate duplicate -> {
                StoredResponse response = duplicate.storedResponse();
                yield ResponseEntity.status(HttpStatus.OK).body(response);
            }

            case PaymentKeyConflict()  -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };

    }


}
