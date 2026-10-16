package com.github.hurdadd.adapter;

import com.github.hurdadd.adapter.in.web.error.ProblemDetailAdvice;
import com.github.hurdadd.adapter.in.web.payment.PaymentController;
import com.github.hurdadd.application.model.StoredResponse;
import com.github.hurdadd.application.port.in.CreatePaymentUseCase;
import com.github.hurdadd.application.port.in.GetPaymentUseCase;
import com.github.hurdadd.application.port.in.results.PaymentCreated;
import com.github.hurdadd.application.port.in.results.PaymentDuplicate;
import com.github.hurdadd.application.port.in.results.PaymentInFlight;
import com.github.hurdadd.application.port.in.results.PaymentKeyConflict;
import com.github.hurdadd.domain.Currency;
import com.github.hurdadd.domain.MerchantId;
import com.github.hurdadd.domain.Money;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentNotFoundException;
import com.github.hurdadd.domain.PaymentStatus;
import com.github.hurdadd.domain.PspReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test: the controller, the DTO, Bean Validation and the
 * {@code ProblemDetail} advice are all real. Only the use cases are mocked, so
 * no database, Redis, Kafka or PSP is touched.
 */
@WebMvcTest(PaymentController.class)
@Import(ProblemDetailAdvice.class)
class PaymentControllerWebTest {

    private static final String KEY = "order-42";
    private static final UUID MERCHANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String VALID_BODY = """
            {"amount": 100.00, "currency": "USD", "merchantId": "22222222-2222-2222-2222-222222222222"}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CreatePaymentUseCase createPaymentUseCase;

    @MockitoBean
    private GetPaymentUseCase getPaymentUseCase;

    private Payment createdPayment() {
        return Payment.create(new Money(new BigDecimal("100.00"), Currency.USD),
                MerchantId.from(MERCHANT), PspReference.generate());
    }

    // ---------- POST /payments ----------

    @Test
    void creates_payment_and_returns_201() throws Exception {
        given(createPaymentUseCase.createPayment(any())).willReturn(new PaymentCreated(createdPayment()));

        mvc.perform(post("/payments")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.paymentId").isNotEmpty())
                .andExpect(jsonPath("$.status").value(PaymentStatus.CREATED.name()));
    }

    @Test
    void replaying_the_same_key_returns_the_stored_response() throws Exception {
        String body = """
                {"paymentId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","status":"CREATED"}""";
        given(createPaymentUseCase.createPayment(any()))
                .willReturn(new PaymentDuplicate(new StoredResponse(201, body)));

        mvc.perform(post("/payments")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(content().json(body));
    }

    @Test
    void same_key_with_a_different_body_is_409() throws Exception {
        given(createPaymentUseCase.createPayment(any())).willReturn(new PaymentKeyConflict());

        mvc.perform(post("/payments")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void key_still_in_flight_is_409_with_retry_after() throws Exception {
        given(createPaymentUseCase.createPayment(any())).willReturn(new PaymentInFlight());

        mvc.perform(post("/payments")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "2"));
    }

    @Test
    void negative_amount_is_422_and_never_reaches_the_use_case() throws Exception {
        mvc.perform(post("/payments")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": -5, "currency": "USD", "merchantId": "22222222-2222-2222-2222-222222222222"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.amount").isNotEmpty());

        verify(createPaymentUseCase, never()).createPayment(any());
    }

    @Test
    void missing_merchant_id_is_422() throws Exception {
        mvc.perform(post("/payments")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 100.00, "currency": "USD"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.merchantId").isNotEmpty());
    }

    @Test
    void blank_currency_is_422() throws Exception {
        mvc.perform(post("/payments")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 100.00, "currency": "  ", "merchantId": "22222222-2222-2222-2222-222222222222"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.currency").isNotEmpty());
    }

    @Test
    void unknown_currency_is_422_from_the_domain() throws Exception {
        mvc.perform(post("/payments")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 100.00, "currency": "XYZ", "merchantId": "22222222-2222-2222-2222-222222222222"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://openpay.dev/problems/domain-rule-violated"));
    }

    @Test
    void malformed_json_is_400() throws Exception {
        mvc.perform(post("/payments")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 100.00, "currency": """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://openpay.dev/problems/malformed-request-body"));
    }

    @Test
    void missing_idempotency_header_is_400() throws Exception {
        mvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header").value("idempotency-key"));

        verify(createPaymentUseCase, never()).createPayment(any());
    }

    // ---------- GET /payments/{id} ----------

    @Test
    void returns_current_status_for_a_known_payment() throws Exception {
        Payment payment = createdPayment();
        given(getPaymentUseCase.getPayment(any())).willReturn(payment);

        mvc.perform(get("/payments/{id}", payment.getPaymentId().getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(payment.getPaymentId().getValue().toString()))
                .andExpect(jsonPath("$.status").value(PaymentStatus.CREATED.name()));
    }

    @Test
    void unknown_payment_is_404_with_problem_detail() throws Exception {
        UUID missing = UUID.fromString("99999999-9999-9999-9999-999999999999");
        given(getPaymentUseCase.getPayment(any()))
                .willThrow(new PaymentNotFoundException("No payment with id " + missing));

        mvc.perform(get("/payments/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://openpay.dev/problems/payment-not-found"));
    }

    @Test
    void malformed_uuid_in_path_is_400_not_a_domain_violation() throws Exception {
        mvc.perform(get("/payments/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://openpay.dev/problems/malformed-request"));
    }

    @Test
    void problem_detail_carries_the_rfc_9457_members() throws Exception {
        mvc.perform(post("/payments")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 0, "currency": "USD", "merchantId": "22222222-2222-2222-2222-222222222222"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Unprocessable content"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.instance").value("/payments"))
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/problem+json")));
    }
}
