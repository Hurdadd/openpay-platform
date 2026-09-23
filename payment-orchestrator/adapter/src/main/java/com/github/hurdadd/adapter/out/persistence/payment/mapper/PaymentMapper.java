package com.github.hurdadd.adapter.out.persistence.payment.mapper;

import com.github.hurdadd.domain.MerchantId;
import com.github.hurdadd.domain.Money;
import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentId;
import com.github.hurdadd.domain.PspReference;
import com.github.hurdadd.adapter.out.persistence.payment.PaymentEntity;
import org.springframework.stereotype.Component;


@Component
public class PaymentMapper {
    private Money money(PaymentEntity entity){
        return new Money(entity.getAmount() , entity.getCurrency());

    }

    private PspReference pspReference(PaymentEntity entity){
        return entity.getPspReference() == null ? null : new PspReference(entity.getPspReference());
    }

    public Payment toDomain(PaymentEntity entity){
        return Payment.reconstitute(money(entity)
                , PaymentId.from(entity.getPaymentId())
                , MerchantId.from(entity.getMerchantId())
                , entity.getStatus()
                , entity.getCreatedAt()
                , entity.getVersion()
                , pspReference(entity));
    }

    public PaymentEntity toEntity(Payment payment){
        return PaymentEntity.builder().amount(payment.getMoney().getAmount())
                .currency(payment.getMoney().getCurrency())
                .merchantId(payment.getMerchantId().getValue())
                .paymentId(payment.getPaymentId().getValue())
                .status(payment.getPaymentStatus())
                .version(payment.getVersion())
                .pspReference(payment.getPspReference() == null ? null : payment.getPspReference().value())
                .build();
    }
}
