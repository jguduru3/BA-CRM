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
    /** Decrypt-only fallback while rotating ENCRYPTION_KEY (set ENCRYPTION_KEY_PREVIOUS to the old key). */
    private static volatile SecretKeySpec previousKey;

    public SecretCrypto(@Value("${app.encryption-key:}") String encryptionKey,
                        @Value("${app.encryption-key-previous:}") String previousEncryptionKey,
                        @Value("${app.jwt.secret}") String jwtSecret,
                        @Value("${app.jwt.enforce-strong-secret:true}") boolean enforceStrong) {
        boolean provided = encryptionKey != null && !encryptionKey.isBlank();
        if (enforceStrong && (!provided || encryptionKey.length() < 32 || encryptionKey.startsWith("change_me"))) {
            throw new IllegalStateException(
                    "ENCRYPTION_KEY must be set to a random value of at least 32 characters "
                    + "(generate one with: openssl rand -hex 32)");
        }
        // Local/test only: derive a stable dev key so no setup is needed.
        String material = provided ? encryptionKey : "better-crm/secret-crypto/v1/" + jwtSecret;
        key = new SecretKeySpec(sha256(material), "AES");
        previousKey = (previousEncryptionKey == null || previousEncryptionKey.isBlank())
                ? null : new SecretKeySpec(sha256(previousEncryptionKey), "AES");
    }

    /** True when a stored value is still plaintext or was written under the previous key. */
    public static boolean needsReencryption(String stored) {
        if (stored == null || stored.isEmpty()) return false;
        if (!stored.startsWith(PREFIX)) return true;
        return tryDecrypt(stored, key) == null && previousKey != null && tryDecrypt(stored, previousKey) != null;
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
        String v = tryDecrypt(stored, key);
        if (v == null && previousKey != null) v = tryDecrypt(stored, previousKey);
        // Unknown key or corrupted value — surface as "not configured" rather than a 500.
        return v;
    }

    private static String tryDecrypt(String stored, SecretKeySpec k) {
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** "••••1234" hint so the UI can show which secret is configured without revealing it. */
    public static String hint(String secret) {
        if (secret == null || secret.isBlank()) return null;
        String s = secret.trim();
        if (s.length() <= 8) return "••••";
        return "••••" + s.substring(s.length() - 4);
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
