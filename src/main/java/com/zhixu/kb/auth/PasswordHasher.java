package com.zhixu.kb.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** PBKDF2-HMAC-SHA256, same parameters as the previous version of the service. */
public final class PasswordHasher {
    private static final int ITERATIONS = 240_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    public static String hash(String password, String saltHex) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), HexFormat.of().parseHex(saltHex), ITERATIONS, 256);
        try {
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Password hashing is unavailable", ex);
        } finally {
            spec.clearPassword();
        }
    }

    public static boolean verify(String password, String salt, String expected) {
        return MessageDigest.isEqual(hash(password, salt).getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }
}
