package com.github.hurdadd.adapter.psp;

import com.github.hurdadd.adapter.psp.exception.PspException;
import com.github.hurdadd.domain.PspReference;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspOutcome;
import com.github.hurdadd.application.port.out.psp.PspResult;

import java.util.Random;
import java.util.UUID;

public class FlakyPsp implements PspClient {
    private final Random random = new Random();
    private final double failureRate ;

    public FlakyPsp (double failureRate){
        if (failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("failureRate must be between 0 and 1");
        }
        this.failureRate = failureRate;
    }

    @Override
    public PspResult authorize(AuthorizationRequest dto) {


        if (random.nextDouble() < failureRate){
            throw new PspException("FlakyPsp failed to authorize the request"  );


        } else {
            return new PspResult(
                    PspOutcome.APPROVED,
                    new PspReference(UUID.randomUUID().toString())
            );
        }
    }

    @Override
    public PspResult checkStatus(PspReference reference) {
        if (random.nextDouble() < failureRate) {
            throw new PspException("FlakyPsp failed to check status");
        }
        return new PspResult(PspOutcome.APPROVED, reference);
    }
}
