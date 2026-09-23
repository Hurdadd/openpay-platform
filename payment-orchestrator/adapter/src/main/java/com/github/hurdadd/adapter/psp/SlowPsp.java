package com.github.hurdadd.adapter.psp;

import com.github.hurdadd.adapter.psp.exception.PspException;
import com.github.hurdadd.domain.PspReference;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspOutcome;
import com.github.hurdadd.application.port.out.psp.PspResult;

import java.util.UUID;

public class SlowPsp implements PspClient {

    @Override
    public PspResult authorize(AuthorizationRequest request) {
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PspException("SlowPsp interrupted during authorize", e);
        }

        return new PspResult(
                PspOutcome.APPROVED,
                new PspReference(UUID.randomUUID().toString())
        );
    }

    @Override
    public PspResult checkStatus(PspReference reference) {
        return new PspResult(PspOutcome.APPROVED, reference);
    }
}