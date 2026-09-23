package com.github.hurdadd.adapter.psp.callback;

import com.github.hurdadd.application.port.out.psp.PspSecretResolver;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationProperties(prefix = "psp")
public class PropertiesPspSecretResolver implements PspSecretResolver {

    // pspId -> HMAC shared secret, e.g. psp.secrets.mellat=xxxx in application.yml
    private Map<String, String> secrets = Map.of();

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets;
    }

    @Override
    public String resolve(String pspId) {
        String secret = secrets.get(pspId);
        if (secret == null) {
            throw new IllegalArgumentException("No HMAC secret configured for pspId " + pspId);
        }
        return secret;
    }
}
