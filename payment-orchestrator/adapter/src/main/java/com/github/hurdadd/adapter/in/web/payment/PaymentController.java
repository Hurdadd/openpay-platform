package com.github.hurdadd.adapter.in.web.payment;

import com.github.hurdadd.adapter.in.web.payment.dto.CreatePaymentRequest;
import com.github.hurdadd.adapter.in.web.payment.dto.PaymentResponse;
import com.github.hurdadd.adapter.in.web.payment.mapper.CreatePaymentMapper;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.application.port.in.CreatePaymentCommand;
import com.github.hurdadd.application.port.in.CreatePaymentUseCase;
import com.github.hurdadd.application.port.in.GetPaymentUseCase;
import com.github.hurdadd.application.port.in.results.PaymentCreated;
import com.github.hurdadd.application.port.in.results.PaymentDuplicate;
import com.github.hurdadd.application.port.in.results.PaymentInFlight;
import com.github.hurdadd.application.port.in.results.PaymentKeyConflict;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentId;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    // PSP authorize budget is 1s, so a retry after 2s normally finds the stored response
    private static final String RETRY_AFTER_SECONDS = "2";

    private final CreatePaymentUseCase createPaymentUseCase;
    private final GetPaymentUseCase getPaymentUseCase;

    public PaymentController(
            CreatePaymentUseCase createPaymentUseCase,
            GetPaymentUseCase getPaymentUseCase
    ) {
        this.createPaymentUseCase = createPaymentUseCase;
        this.getPaymentUseCase = getPaymentUseCase;
    }

    @PostMapping
    public ResponseEntity<?> createPayment(
            @RequestHeader("idempotency-key") String idempotencyKey,
            @RequestBody @Valid CreatePaymentRequest body
    ) {
        CreatePaymentCommand command = CreatePaymentMapper.toCommand(body, idempotencyKey);

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

            // the first request with this key is still running: tell the client to retry, never 500
            case PaymentInFlight ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                            .body("request in progress");
        };
    }


    /**
     * The path variable arrives as a raw {@code String} and is translated to
     * {@link PaymentId} through the mapper — the same boundary rule the create
     * path follows for {@code MerchantId}. Binding it as {@code UUID} directly
     * would let {@code UUID.fromString} throw a bare
     * {@code IllegalArgumentException}, which the error layer cannot tell apart
     * from a domain rule violation.
     */
    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@PathVariable String paymentId) {
        Payment payment = getPaymentUseCase.getPayment(CreatePaymentMapper.toPaymentId(paymentId));
        return new PaymentResponse(payment.getPaymentId().getValue(), payment.getPaymentStatus());
    }

    private ResponseEntity<String> replayStoredResponse(StoredResponse stored) {
        return ResponseEntity
                .status(stored.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(stored.body());
    }
}
