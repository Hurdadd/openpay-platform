package com.github.hurdadd.application.port.out;



import com.github.hurdadd.domain.Payment;
import com.github.hurdadd.domain.PaymentId;
import com.github.hurdadd.domain.PspReference;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository {

     Payment save(Payment payment);
     Optional<Payment> findById(PaymentId paymentId);
     Optional<Payment> findByPspReference(PspReference pspReference);

     // CREATED/PENDING_PSP/AUTHORIZED payments with a psp reference whose status last changed before `olderThan` and after `newerThan` (the age cap) — reconciler's candidate pool
     List<Payment> findStuckPending(Instant olderThan, Instant newerThan);

}
