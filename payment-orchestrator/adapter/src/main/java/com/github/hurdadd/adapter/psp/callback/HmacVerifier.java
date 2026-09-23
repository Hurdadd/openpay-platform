package com.github.hurdadd.adapter.psp.callback;

import com.github.hurdadd.application.port.out.psp.PspSecretResolver;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class HmacVerifier {

    private final PspSecretResolver secretResolver;

    public HmacVerifier(PspSecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    public boolean verify(String pspId,
                          byte[] rawPayload,
                          String receivedSignature){
       String secret =  secretResolver.resolve(pspId);
         byte[] calculatedSignature = hmacSha256(rawPayload, secret);

        return MessageDigest.isEqual(calculatedSignature, HexFormat.of().parseHex(receivedSignature));
    }


    private byte[] hmacSha256(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data);
            return hmacBytes;
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC-SHA256", e);
        }
    }
}
