package com.github.hurdadd.domain;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Payment {


    private final Money money;

    private final PaymentId paymentId;

    private final MerchantId merchantId;

    private  PaymentStatus paymentStatus;

    private final Instant createdAt;

    private Instant statusChangedAt;

    private long version;

    private PspReference pspReference;

    private final List<PaymentEvent> events = new ArrayList<>();

    private Payment(Money money, PaymentId paymentId, MerchantId merchantId, PaymentStatus paymentStatus, Instant createdAt, Instant statusChangedAt, long version, PspReference pspReference) {
        this.money = money;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.paymentStatus = paymentStatus;
        this.version = version;
        this.createdAt = createdAt;
        this.statusChangedAt = statusChangedAt;
        this.pspReference = pspReference;
    }


    public static Payment create(Money money , MerchantId merchantId , PspReference pspReference) {
        Instant now = Instant.now();
        return new Payment(money, PaymentId.generate(), merchantId, PaymentStatus.CREATED, now, now, 0, pspReference);
    }

    public static Payment reconstitute(Money money, PaymentId paymentId, MerchantId merchantId, PaymentStatus paymentStatus, Instant createdAt, Instant statusChangedAt, long version, PspReference pspReference) {
        return new Payment(money, paymentId, merchantId, paymentStatus, createdAt, statusChangedAt, version, pspReference);
    }

    public static Payment reconstitute(Money money, PaymentId paymentId, MerchantId merchantId, PaymentStatus paymentStatus, Instant createdAt, long version, PspReference pspReference) {
        return new Payment(money, paymentId, merchantId, paymentStatus, createdAt, createdAt, version, pspReference);
    }
    private void transitionTo(PaymentStatus target) {
        if (!this.paymentStatus.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Illegal transition: " + this.paymentStatus + " → " + target);
        }
        this.paymentStatus = target;
        this.statusChangedAt = Instant.now();
    }
    public void fail(){transitionTo(PaymentStatus.FAILED);}
    public void authorize(){transitionTo(PaymentStatus.AUTHORIZED);}
    public void reverse(){transitionTo(PaymentStatus.REVERSED);}
    public void settle() {
        transitionTo(PaymentStatus.SETTLED);
        events.add(new PaymentSettled(paymentId, merchantId, money, Instant.now()));
    }

    // the repository adapter drains these in save() and writes them to the outbox in the same transaction
    public List<PaymentEvent> pullEvents() {
        List<PaymentEvent> drained = List.copyOf(events);
        events.clear();
        return drained;
    }

    public void markPending(){
        // validate before mutating: a payment without a reference must not change state
        Objects.requireNonNull(pspReference, "pspReference cannot be null");
        transitionTo(PaymentStatus.PENDING_PSP);
    }


    public Money getMoney() {
        return money;
    }

    public PaymentId getPaymentId() {
        return paymentId;
    }

    public MerchantId getMerchantId() {
        return merchantId;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    // when the status last changed; the reconciler measures "stuck" from here, not from createdAt
    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public PspReference getPspReference() {
        return pspReference;
    }
}
