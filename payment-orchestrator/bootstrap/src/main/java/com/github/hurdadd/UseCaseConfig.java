package com.github.hurdadd;

import com.github.hurdadd.application.port.in.CreatePaymentUseCase;
import com.github.hurdadd.application.port.out.IdempotencyStore;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.service.CreatePaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class UseCaseConfig {

    @Bean
    CreatePaymentUseCase createPaymentUseCase(
            PaymentRepository repository,
            IdempotencyStore idempotencyStore,
            PlatformTransactionManager transactionManager
    ) {
        CreatePaymentService delegate =
                new CreatePaymentService(repository, idempotencyStore);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        return command -> {
            var result = transactionTemplate.execute(
                    status -> delegate.createPayment(command)
            );
            if (result == null) {
                throw new IllegalStateException(
                        "Create payment transaction returned no result"
                );
            }
            return result;
        };
    }
}
