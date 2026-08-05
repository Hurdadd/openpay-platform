package com.github.hurdadd.payment.persistence.payment.mapper;

import com.github.hurdadd.payment.MerchantId;
import com.github.hurdadd.payment.Money;
import com.github.hurdadd.payment.Payment;
import com.github.hurdadd.payment.PaymentId;
import com.github.hurdadd.payment.persistence.payment.PaymentEntity;
import org.springframework.stereotype.Component;


@Component
public class PaymentMapper {
    private Money money(PaymentEntity entity){
        return new Money(entity.getAmount() , entity.getCurrency());

    }

    public Payment toDomain(PaymentEntity entity){
        return Payment.reconstitute(money(entity)
                , PaymentId.from(entity.getPaymentId())
                , MerchantId.from(entity.getMerchantId())
                , entity.getStatus()
                , entity.getCreatedAt()
                , entity.getVersion());
    }

    public PaymentEntity toEntity(Payment payment){
        return PaymentEntity.builder().amount(payment.getMoney().getAmount())
                .currency(payment.getMoney().getCurrency())
                .merchantId(payment.getMerchantId().getValue())
                .paymentId(payment.getPaymentId().getValue())
                .status(payment.getPaymentStatus())
                .version(payment.getVersion())
                .build();
    }
}
