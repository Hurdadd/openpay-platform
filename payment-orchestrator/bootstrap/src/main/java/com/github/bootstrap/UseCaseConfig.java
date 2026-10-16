package com.github.bootstrap;

import com.github.hurdadd.application.port.in.CreatePaymentUseCase;
import com.github.hurdadd.application.port.in.GetPaymentUseCase;
import com.github.hurdadd.application.port.out.IdempotencyStore;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.port.out.TxRunner;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.service.CreatePaymentService;
import com.github.hurdadd.application.service.GetPaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Configuration
public class UseCaseConfig {

    @Bean
    TxRunner txRunner(PlatformTransactionManager transactionManager,
                      @Value("${tx.timeout-seconds}") int timeoutSeconds) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // only these use-case transactions are bounded; the outbox publisher legitimately holds its
        // transaction open while waiting for Kafka
        template.setTimeout(timeoutSeconds);
        return new TxRunner() {
            @Override
            public <T> T inTx(Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
    }

    @Bean
    CreatePaymentUseCase createPaymentUseCase(
            PaymentRepository repository,
            IdempotencyStore idempotencyStore,
            PspClient pspClient,
            TxRunner tx
    ) {
        return new CreatePaymentService(repository, idempotencyStore, pspClient, tx);
    }

    @Bean
    GetPaymentUseCase getPaymentUseCase(PaymentRepository repository) {
        return new GetPaymentService(repository);
    }
}
