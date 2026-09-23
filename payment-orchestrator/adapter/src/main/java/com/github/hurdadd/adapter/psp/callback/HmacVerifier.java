package com.github.hurdadd.adapter.psp.callback;

import com.github.hurdadd.application.port.out.psp.PspSecretResolver;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class HmacVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final PspSecretResolver secretResolver;

    public HmacVerifier(PspSecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    public boolean verify(
            String pspId,
            long timestampEpochSeconds,
            String nonce,
            byte[] rawPayload,
            String receivedSignature
    ) {
        if (pspId == null || pspId.isBlank()
                || nonce == null || nonce.isBlank()
                || rawPayload == null
                || receivedSignature == null || receivedSignature.isBlank()) {
            return false;
        }

        final String secret;
        final byte[] providedSignature;

        try {
            secret = secretResolver.resolve(pspId);
            providedSignature = HexFormat.of().parseHex(receivedSignature);
        } catch (IllegalArgumentException e) {
            return false;
        }

        byte[] calculatedSignature = hmacSha256(
                signedPayload(timestampEpochSeconds, nonce, rawPayload),
                secret
        );

        return MessageDigest.isEqual(calculatedSignature, providedSignature);
    }

    private byte[] signedPayload(long timestampEpochSeconds, String nonce, byte[] rawPayload) {
        byte[] prefix = (timestampEpochSeconds + "." + nonce + ".")
                .getBytes(StandardCharsets.UTF_8);

        byte[] signed = new byte[prefix.length + rawPayload.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(rawPayload, 0, signed, prefix.length, rawPayload.length);
        return signed;
    }

    private byte[] hmacSha256(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }
}
