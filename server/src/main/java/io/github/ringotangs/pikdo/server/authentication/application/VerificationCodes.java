package io.github.ringotangs.pikdo.server.authentication.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public final class VerificationCodes {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public VerificationCodes(AuthenticationProperties properties) {
        this(properties.hmacKey());
    }

    public VerificationCodes(String encodedKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid authentication HMAC key");
        }
        if (decoded.length < 32)
            throw new IllegalArgumentException(
                    "Authentication HMAC key requires at least 32 bytes");
        key = new SecretKeySpec(decoded, "HmacSHA256");
    }

    public String generate() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    public String newFlow() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public byte[] digest(String... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            for (String part : parts) {
                byte[] value = part.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(4).putInt(value.length).array());
                mac.update(value);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC is unavailable");
        }
    }

    public boolean matches(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }
}
