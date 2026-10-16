package com.github.hurdadd.adapter.psp;

import com.github.hurdadd.adapter.psp.exception.PspClientException;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspOutcome;
import com.github.hurdadd.application.port.out.psp.PspResult;
import com.github.hurdadd.domain.PspReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Plain HTTP adapter for a real PSP. Timeouts, retries and the breaker are NOT handled here;
 * {@link ResilientPspClient} wraps this class and owns that policy.
 * <p>
 * Contract: {@code POST /authorize} and {@code GET /status/{pspReference}}, both answering
 * {@code {"outcome":"APPROVED|DECLINED|TIMEOUT|REVERSED"}}. A 4xx is a definitive answer (never retried,
 * never counted by the breaker); anything else propagates as a runtime failure = outcome unknown.
 */
public class HttpPspClient implements PspClient {

    private record Answer(PspOutcome outcome) {}

    private final RestClient http;

    public HttpPspClient(RestClient http) {
        this.http = http;
    }

    @Override
    public PspResult authorize(AuthorizationRequest request) {
        Answer answer = call(() -> http.post().uri("/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pspReference", request.pspReference().value(),
                        "merchantReference", request.reference().value(),
                        "amount", request.money().getAmount(),
                        "currency", request.money().getCurrency().name()))
                .retrieve()
                .body(Answer.class));
        return new PspResult(answer.outcome(), request.pspReference());
    }

    @Override
    public PspResult checkStatus(PspReference reference) {
        Answer answer = call(() -> http.get().uri("/status/{ref}", reference.value())
                .retrieve()
                .body(Answer.class));
        return new PspResult(answer.outcome(), reference);
    }

    private Answer call(java.util.function.Supplier<Answer> request) {
        try {
            Answer answer = request.get();
            if (answer == null || answer.outcome() == null) {
                throw new IllegalStateException("PSP answered without an outcome");
            }
            return answer;
        } catch (HttpClientErrorException e) {
            throw new PspClientException("PSP rejected the request: " + e.getStatusCode(), e);
        }
    }
}
