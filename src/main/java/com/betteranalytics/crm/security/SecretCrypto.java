package com.betteranalytics.crm.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for third-party secrets stored in the database
 * (AI provider keys, WhatsApp access tokens, SMTP/IMAP passwords, webhook
 * signing secrets). Values are stored as "enc:v1:" + base64(iv || ciphertext+tag).
 *
 * The key comes from APP_ENCRYPTION_KEY; when that is unset it is derived from
 * JWT_SECRET under a separate label so the two keys are never identical. Values
 * written before encryption existed (no prefix) are read back as-is and get
 * encrypted the next time they are saved — no data migration needed.
 */
@Component
public class SecretCrypto {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile SecretKeySpec key;

    public SecretCrypto(@Value("${app.encryption-key:}") String encryptionKey,
                        @Value("${app.jwt.secret}") String jwtSecret) {
        String material = (encryptionKey != null && !encryptionKey.isBlank())
                ? encryptionKey
                : "better-crm/secret-crypto/v1/" + jwtSecret;
        key = new SecretKeySpec(sha256(material), "AES");
    }

    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || plaintext.startsWith(PREFIX)) return plaintext;
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("secret encryption failed", e);
        }
    }

    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Key rotated or value corrupted — surface as "not configured" rather than a 500.
            return null;
        }
    }

    /** "sk-ant-…a1b2" style hint so the UI can show which key is configured without revealing it. */
    public static String hint(String secret) {
        if (secret == null || secret.isBlank()) return null;
        String s = secret.trim();
        if (s.length() <= 8) return "••••";
        return s.substring(0, Math.min(6, s.length() / 3)) + "…" + s.substring(s.length() - 4);
    }

    private static void requireKey() {
        if (key == null) throw new IllegalStateException("SecretCrypto not initialised");
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
