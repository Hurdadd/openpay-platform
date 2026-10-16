package com.github.bootstrap;

import com.github.hurdadd.adapter.psp.HttpPspClient;
import com.github.hurdadd.adapter.psp.PspResilienceSettings;
import com.github.hurdadd.adapter.psp.ResilientPspClient;
import com.github.hurdadd.adapter.psp.SlowPsp;
import com.github.hurdadd.application.port.out.psp.PspClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(PspResilienceProperties.class)
class PspConfig {

    @Bean
    // psp.base-url set -> talk HTTP to a real/stubbed PSP; unset -> the in-process SlowPsp used by local dev
    PspClient pspClient(PspResilienceProperties resilience, @Value("${psp.base-url:}") String baseUrl) {
        PspClient raw = baseUrl.isBlank() ? new SlowPsp() : new HttpPspClient(restClient(baseUrl));
        return new ResilientPspClient(raw, new PspResilienceSettings(
                resilience.timeout(),
                resilience.maxAttempts(),
                resilience.initialRetryInterval(),
                resilience.retryMultiplier(),
                resilience.retryJitter(),
                resilience.circuitBreakerWaitDuration()
        ));
    }

    // HTTP/1.1 on purpose: the JDK client's default h2c upgrade attempt sent the POST body to the PSP empty
    private static RestClient restClient(String baseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        return RestClient.builder().baseUrl(baseUrl).requestFactory(new JdkClientHttpRequestFactory(http)).build();
    }
}
