package com.github.hurdadd.adapter.in.persistence.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface JpaPaymentEntityRepository extends JpaRepository<PaymentEntity, UUID> {

}
