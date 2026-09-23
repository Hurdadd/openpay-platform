package com.github.hurdadd.adapter.psp.callback;

import tools.jackson.databind.ObjectMapper;
import com.github.hurdadd.adapter.psp.callback.dto.PspCallbackRequest;
import com.github.hurdadd.application.port.in.PspCallbackHandler;
import com.github.hurdadd.application.port.out.security.ReplayGuard;
import com.github.hurdadd.domain.PaymentNotFoundException;
import com.github.hurdadd.domain.PspReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/callbacks/psp")
public class PspCallbackController {

    private final HmacVerifier hmacVerifier;
    private final ReplayGuard replayGuard;
    private final PspCallbackHandler callbackHandler;
    private final ObjectMapper objectMapper;

    public PspCallbackController(HmacVerifier hmacVerifier,
                                  ReplayGuard replayGuard,
                                  PspCallbackHandler callbackHandler,
                                  ObjectMapper objectMapper) {
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
        // signature is verified over the exact bytes the PSP signed — parse only after verification passes
        if (!hmacVerifier.verify(pspId, rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Instant timestamp = Instant.ofEpochSecond(timestampEpochSeconds);
        if (replayGuard.isReplay(nonce, timestamp)) {
            // same nonce already processed (or timestamp is stale/future) — no-op, not an error;
            // the PSP already got its earlier 2xx and will not benefit from a retry signal here
            return ResponseEntity.ok().build();
        }

        PspCallbackRequest body;
        try {
            body = objectMapper.readValue(rawBody, PspCallbackRequest.class);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        try {
            callbackHandler.handle(new PspReference(body.pspReference()), body.status());
        } catch (PaymentNotFoundException e) {
            // an unknown reference is still a valid, verified message — it just doesn't match our records yet;
            // ack it so the PSP doesn't retry forever, our reconciler is the backstop
            return ResponseEntity.ok().build();
        }

        return ResponseEntity.ok().build();
    }
}
