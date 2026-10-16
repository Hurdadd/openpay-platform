package com.github.hurdadd.domain;

import java.util.EnumSet;
import java.util.Set;

public enum PaymentStatus {

    CREATED,
    PENDING_PSP,
    AUTHORIZED,
    FAILED,
    SETTLED,
    REVERSED;

    private Set<PaymentStatus> transitions;

    static {
        CREATED.transitions = EnumSet.of(PENDING_PSP, FAILED);
        PENDING_PSP.transitions = EnumSet.of(AUTHORIZED, FAILED);
        AUTHORIZED.transitions = EnumSet.of(SETTLED, REVERSED);

        FAILED.transitions = EnumSet.noneOf(PaymentStatus.class);
        SETTLED.transitions = EnumSet.noneOf(PaymentStatus.class);
        REVERSED.transitions = EnumSet.noneOf(PaymentStatus.class);
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return transitions.contains(target);
    }

    // true if target is a legal step now or after some further legal steps (e.g. PENDING_PSP -> SETTLED via AUTHORIZED)
    public boolean canReach(PaymentStatus target) {
        return transitions.contains(target) || transitions.stream().anyMatch(next -> next.canReach(target));
    }
}
