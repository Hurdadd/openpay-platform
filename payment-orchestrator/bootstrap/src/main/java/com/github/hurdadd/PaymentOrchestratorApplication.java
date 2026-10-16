package com.github.hurdadd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Lives in the root package so component scan, JPA entities and repositories all see the adapters
// (com.github.hurdadd.adapter); the wiring configs stay in com.github.bootstrap (ADR-0003).
@SpringBootApplication(scanBasePackages = {"com.github.hurdadd", "com.github.bootstrap"})
public class PaymentOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentOrchestratorApplication.class, args);
    }
}
