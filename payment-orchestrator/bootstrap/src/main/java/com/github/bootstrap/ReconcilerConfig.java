package com.github.bootstrap;

import com.github.hurdadd.adapter.out.persistence.lock.JdbcSchedulerLock;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.port.out.TxRunner;
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
            TxRunner tx,
            @Value("${reconciler.stuck-threshold}") Duration stuckThreshold,
            @Value("${reconciler.max-age}") Duration maxAge
    ) {
        return new PaymentReconciler(paymentRepository, pspClient, clock, stuckThreshold, maxAge, tx);
    }

    @Bean
    ReconcilerScheduler reconcilerScheduler(PaymentReconciler reconciler,
                                            JdbcSchedulerLock lock,
                                            @Value("${reconciler.lock-lease:5m}") Duration lockLease) {
        return new ReconcilerScheduler(reconciler, lock, lockLease);
    }

    static class ReconcilerScheduler {
        private static final String LOCK_NAME = "reconciler";

        private final PaymentReconciler reconciler;
        private final JdbcSchedulerLock lock;
        private final Duration lockLease;

        ReconcilerScheduler(PaymentReconciler reconciler, JdbcSchedulerLock lock, Duration lockLease) {
            this.reconciler = reconciler;
            this.lock = lock;
            this.lockLease = lockLease;
        }

        // with several instances only the one holding the lock polls the PSP this round
        @Scheduled(fixedDelayString = "${reconciler.poll-interval}")
        void run() {
            if (!lock.tryAcquire(LOCK_NAME, lockLease)) {
                return;
            }
            try {
                reconciler.reconcile();
            } finally {
                lock.release(LOCK_NAME);
            }
        }
    }
}
