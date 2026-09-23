package com.github.hurdadd;

import com.github.hurdadd.adapter.psp.ResilientPspClient;
import com.github.hurdadd.adapter.psp.SlowPsp;
import com.github.hurdadd.application.port.out.psp.PspClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class PspConfig {

    @Bean
    PspClient pspClient() {
        PspClient raw = new SlowPsp();
        return new ResilientPspClient(raw);
    }

}