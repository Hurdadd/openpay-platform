package com.github.hurdadd.domain;


import java.time.Instant;
import java.util.Objects;

public final class Payment {


    private final Money money;

    private final PaymentId paymentId;

    private final MerchantId merchantId;

    private  PaymentStatus paymentStatus;

    private final Instant createdAt;

    private long version;

    private PspReference pspReference;

    private Payment(Money money, PaymentId paymentId, MerchantId merchantId, PaymentStatus paymentStatus, Instant createdAt, long version, PspReference pspReference) {
        this.money = money;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.paymentStatus = paymentStatus;
        this.version = version;
        this.createdAt = createdAt;
        this.pspReference = pspReference;
    }


    public static Payment create(Money money , MerchantId merchantId) {
        return new Payment(money, PaymentId.generate(), merchantId, PaymentStatus.CREATED, Instant.now() , 0, null);
    }

    public static Payment reconstitute(Money money, PaymentId paymentId, MerchantId merchantId, PaymentStatus paymentStatus, Instant createdAt, long version, PspReference pspReference) {
        return new Payment(money, paymentId, merchantId, paymentStatus, createdAt , version, pspReference);
    }
    private void transitionTo(PaymentStatus target) {
        if (!this.paymentStatus.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Illegal transition: " + this.paymentStatus + " → " + target);
        }
        this.paymentStatus = target;
    }
    public void fail(){transitionTo(PaymentStatus.FAILED);}
    public void authorize(){transitionTo(PaymentStatus.AUTHORIZED);}
    public void reverse(){transitionTo(PaymentStatus.REVERSED);}
    public void settle(){transitionTo(PaymentStatus.SETTLED);}

    public void markPending(PspReference pspReference){
        transitionTo(PaymentStatus.PENDING_PSP);
        this.pspReference = Objects.requireNonNull(pspReference, "pspReference cannot be null");
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
