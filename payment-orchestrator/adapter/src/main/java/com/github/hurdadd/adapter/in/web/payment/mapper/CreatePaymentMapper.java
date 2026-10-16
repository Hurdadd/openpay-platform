package com.github.hurdadd.adapter.in.web.payment.mapper;

import com.github.hurdadd.adapter.in.web.error.MalformedRequestException;
import com.github.hurdadd.adapter.in.web.payment.dto.CreatePaymentRequest;
import com.github.hurdadd.application.port.in.CreatePaymentCommand;
import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.MerchantId;
import com.github.hurdadd.domain.PaymentId;

import java.util.UUID;


public final class CreatePaymentMapper {

    private CreatePaymentMapper() {
        throw new AssertionError("utility class");
    }

    public static CreatePaymentCommand toCommand(CreatePaymentRequest request, String idempotencyKey) {
        return new CreatePaymentCommand(
                request.amount(),
                toCurrency(request.currency()),
                MerchantId.from(request.merchantId()),
                idempotencyKey
        );
    }

    /**
     * Parses a path variable into a {@link PaymentId}. A malformed identifier is
     * a request-syntax error, not a domain violation, so it is raised as
     * {@link MalformedRequestException} and surfaces as 400.
     */
    public static PaymentId toPaymentId(String raw) {
        try {
            return PaymentId.from(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("Payment id is not a valid UUID: " + raw, e);
        }
    }

    private static Currency toCurrency(String code) {
        return switch (code) {
            case "IRR" -> Currency.IRR;
            case "USD" -> Currency.USD;
            case "EUR" -> Currency.EUR;
            case "AUD" -> Currency.AUD;
            default -> throw new IllegalArgumentException("unknown currency: " + code);
        };
    }
}