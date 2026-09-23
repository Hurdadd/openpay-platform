package com.github.hurdadd.adapter.psp.callback;

import com.github.hurdadd.adapter.psp.callback.dto.PspCallbackRequest;
import com.github.hurdadd.application.port.in.PspCallbackHandler;
import com.github.hurdadd.application.port.out.security.ReplayGuard;
import com.github.hurdadd.domain.PaymentNotFoundException;
import com.github.hurdadd.domain.PspReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@RestController
@RequestMapping("/callbacks/psp")
public class PspCallbackController {

    private final HmacVerifier hmacVerifier;
    private final ReplayGuard replayGuard;
    private final PspCallbackHandler callbackHandler;
    private final ObjectMapper objectMapper;

    public PspCallbackController(
            HmacVerifier hmacVerifier,
            ReplayGuard replayGuard,
            PspCallbackHandler callbackHandler,
            ObjectMapper objectMapper
    ) {
        this.hmacVerifier = hmacVerifier;
        this.replayGuard = replayGuard;
        this.callbackHandler = callbackHandler;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{pspId}")
    public ResponseEntity<?> receive(
            @PathVariable String pspId,
            @RequestHeader("X-Psp-Signature") String signature,
            @RequestHeader("X-Psp-Timestamp") long timestampEpochSeconds,
            @RequestHeader("X-Psp-Nonce") String nonce,
            @RequestBody byte[] rawBody
    ) {
        if (nonce.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Authenticate timestamp + nonce + exact raw body together.
        // If timestamp/nonce were not covered by the HMAC, a captured body/signature
        // could be replayed with fresh replay headers.
        if (!hmacVerifier.verify(pspId, timestampEpochSeconds, nonce, rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Instant timestamp = Instant.ofEpochSecond(timestampEpochSeconds);
        if (replayGuard.isReplay(pspId, nonce, timestamp)) {
            return ResponseEntity.ok().build();
        }

        final PspCallbackRequest body;
        try {
            body = objectMapper.readValue(rawBody, PspCallbackRequest.class);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }

        try {
            callbackHandler.handle(new PspReference(body.pspReference()), body.status());
        } catch (PaymentNotFoundException e) {
            // Verified PSP callback with an unknown reference is acknowledged.
            // Reconciliation is the recovery path for our local payment state.
            return ResponseEntity.ok().build();
        }

        return ResponseEntity.ok().build();
    }
}
