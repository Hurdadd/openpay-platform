package com.github.bootstrap;

import com.github.hurdadd.adapter.psp.callback.HmacVerifier;
import com.github.hurdadd.application.port.in.PspCallbackHandler;
import com.github.hurdadd.application.port.out.PaymentRepository;
import com.github.hurdadd.application.port.out.TxRunner;
import com.github.hurdadd.application.port.out.psp.PspSecretResolver;
import com.github.hurdadd.application.service.PspCallbackService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CallbackConfig {

    @Bean
    PspCallbackHandler pspCallbackHandler(PaymentRepository paymentRepository, TxRunner tx) {
        return new PspCallbackService(paymentRepository, tx);
    }

    @Bean
    HmacVerifier hmacVerifier(PspSecretResolver secretResolver) {
        return new HmacVerifier(secretResolver);
    }
}
