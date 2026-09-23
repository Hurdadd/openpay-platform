package com.github.hurdadd;

import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.port.out.psp.PspClient;
import com.github.hurdadd.application.service.PaymentReconciler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableScheduling
public class ReconcilerConfig {

    @Bean
    PaymentReconciler paymentReconciler(
            PaymentRepository paymentRepository,
            PspClient pspClient,
            Clock clock,
            @Value("${reconciler.stuck-threshold}") Duration stuckThreshold,
            @Value("${reconciler.max-age}") Duration maxAge
    ) {
        return new PaymentReconciler(paymentRepository, pspClient, clock, stuckThreshold, maxAge);
    }

    @Bean
    ReconcilerScheduler reconcilerScheduler(PaymentReconciler reconciler) {
        return new ReconcilerScheduler(reconciler);
    }

    static class ReconcilerScheduler {
        private final PaymentReconciler reconciler;

        ReconcilerScheduler(PaymentReconciler reconciler) {
            this.reconciler = reconciler;
        }

        @Scheduled(fixedDelayString = "${reconciler.poll-interval}")
        void run() {
            reconciler.reconcile();
        }
    }
}
