package com.github.hurdadd.payment.port.out;



import com.github.hurdadd.payment.Payment;
import com.github.hurdadd.payment.PaymentId;

import java.util.Optional;

public interface PaymentRepository {

     Payment save(Payment payment);
     Optional<Payment> findById(PaymentId paymentId);

}
