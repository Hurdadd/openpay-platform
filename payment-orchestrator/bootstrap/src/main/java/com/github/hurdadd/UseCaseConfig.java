package com.github.hurdadd;


import com.github.hurdadd.payment.port.in.CreatePaymentUseCase;
import com.github.hurdadd.payment.port.out.IdempotencyStore;
import com.github.hurdadd.payment.port.out.PaymentRepository;
import com.github.hurdadd.payment.service.CreatePaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UseCaseConfig {
    @Bean
    CreatePaymentUseCase createPaymentUseCase(PaymentRepository repo, IdempotencyStore store) {
        return new CreatePaymentService(repo , store);
    }
}
