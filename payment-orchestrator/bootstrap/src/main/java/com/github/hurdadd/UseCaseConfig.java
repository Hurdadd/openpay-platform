package com.github.hurdadd;



import com.github.hurdadd.application.port.in.CreatePaymentUseCase;
import com.github.hurdadd.application.port.out.IdempotencyStore;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.service.CreatePaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UseCaseConfig {
    @Bean
    CreatePaymentUseCase createPaymentUseCase(PaymentRepository repo, IdempotencyStore store) {
        return new CreatePaymentService(repo , store);
    }
}
