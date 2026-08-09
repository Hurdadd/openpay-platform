package com.github.hurdadd.application.port.out;



import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentId;

import java.util.Optional;

public interface PaymentRepository {

     Payment save(Payment payment);
     Optional<Payment> findById(PaymentId paymentId);

}
