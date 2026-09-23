package com.github.hurdadd.adapter.psp.callback;

import com.github.hurdadd.application.port.out.psp.PspSecretResolver;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmacVerifierTest {

    private static final String PSP_ID = "slow-psp";
    private static final String SECRET = "test-secret";
    private static final long TIMESTAMP = 1_800_000_000L;
    private static final String NONCE = "nonce-123";
    private static final byte[] BODY =
            "{\"pspReference\":\"ref-1\",\"status\":\"AUTHORIZED\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private final PspSecretResolver resolver = pspId -> {
        if (!PSP_ID.equals(pspId)) {
            throw new IllegalArgumentException("unknown psp");
        }
        return SECRET;
    };

    private final HmacVerifier verifier = new HmacVerifier(resolver);

    @Test
    void valid_signature_authenticates_timestamp_nonce_and_body() {
        String signature = sign(TIMESTAMP, NONCE, BODY);

        assertTrue(verifier.verify(PSP_ID, TIMESTAMP, NONCE, BODY, signature));
    }

    @Test
    void changing_replay_headers_invalidates_captured_signature() {
        String signature = sign(TIMESTAMP, NONCE, BODY);

        assertFalse(verifier.verify(PSP_ID, TIMESTAMP + 1, NONCE, BODY, signature));
        assertFalse(verifier.verify(PSP_ID, TIMESTAMP, "fresh-nonce", BODY, signature));
    }

    @Test
    void changing_body_invalidates_signature() {
        String signature = sign(TIMESTAMP, NONCE, BODY);
        byte[] tampered = "{\"pspReference\":\"ref-2\",\"status\":\"AUTHORIZED\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier.verify(PSP_ID, TIMESTAMP, NONCE, tampered, signature));
    }

    @Test
    void malformed_or_unknown_signature_input_is_rejected_not_thrown() {
        assertFalse(verifier.verify(PSP_ID, TIMESTAMP, NONCE, BODY, "not-hex"));
        assertFalse(verifier.verify("unknown", TIMESTAMP, NONCE, BODY, "00"));
        assertFalse(verifier.verify(PSP_ID, TIMESTAMP, "", BODY, "00"));
    }

    private String sign(long timestamp, String nonce, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            byte[] prefix = (timestamp + "." + nonce + ".").getBytes(StandardCharsets.UTF_8);
            byte[] signed = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, signed, 0, prefix.length);
            System.arraycopy(body, 0, signed, prefix.length, body.length);

            return HexFormat.of().formatHex(mac.doFinal(signed));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
