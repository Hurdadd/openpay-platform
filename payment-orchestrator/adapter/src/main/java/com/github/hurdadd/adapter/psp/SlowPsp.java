package com.github.hurdadd.adapter.psp;

import com.github.hurdadd.adapter.psp.exception.PspException;
import com.github.hurdadd.application.port.out.psp.AuthorizationRequest;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.port.out.psp.PspOutcome;
import com.github.hurdadd.application.port.out.psp.PspResult;
import com.github.hurdadd.domain.PspReference;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process PSP simulator for local runs. It answers after ~800 ms, echoes the caller's reference
 * (so the reconciler path asks about the same reference the orchestrator minted) and remembers the
 * outcome per reference. Amount {@value #DECLINE_AMOUNT} is always declined, which gives the dev
 * setup a DECLINED scenario without WireMock.
 */
public class SlowPsp implements PspClient {

    public static final String DECLINE_AMOUNT = "13.13";

    private final Map<String, PspOutcome> outcomes = new ConcurrentHashMap<>();

    @Override
    public PspResult authorize(AuthorizationRequest request) {
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PspException("SlowPsp interrupted during authorize", e);
        }

        PspOutcome outcome = request.money().getAmount().compareTo(new BigDecimal(DECLINE_AMOUNT)) == 0
                ? PspOutcome.DECLINED
                : PspOutcome.APPROVED;
        outcomes.put(request.pspReference().value(), outcome);
        return new PspResult(outcome, request.pspReference());
    }

    @Override
    public PspResult checkStatus(PspReference reference) {
        // an unknown reference means the authorize call never reached us (or we restarted): approved by default
        return new PspResult(outcomes.getOrDefault(reference.value(), PspOutcome.APPROVED), reference);
    }
}
