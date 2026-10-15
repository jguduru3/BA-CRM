package com.betteranalytics.crm.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Outside the local and test profiles (enforce-strong-secret=true) the app must
 * refuse to start with a missing, short or published JWT secret / encryption key.
 */
class StartupSecretsTest {

    private static final String STRONG = "0123456789abcdef0123456789abcdef-strong";
    private static final String TEST_KEY = "test-encryption-key-0123456789-0123456789";
    private static final String TEST_JWT = "test-secret-test-secret-test-secret-0123456789";

    @AfterAll
    static void restoreTestKey() {
        // SecretCrypto keeps its key statically; put back the test-profile key for later tests.
        new SecretCrypto(TEST_KEY, "", TEST_JWT, false);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "short",
            "0123456789012345678901234567890",              // 31 chars
            "change_this_to_a_long_random_string_in_production",
            "change_me_in_production_use_32plus_chars",
            "change_me_to_a_long_random_string_at_least_32_chars"})
    void weakJwtSecretFailsStartup(String secret) {
        assertThatThrownBy(() -> new JwtUtil(secret, 3600_000, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void jwtSecretLengthIsMeasuredInBytes() {
        // 16 two-byte characters = 32 bytes: accepted; 31 ASCII chars: rejected above.
        assertThatCode(() -> new JwtUtil("éééééééééééééééé", 3600_000, true)).doesNotThrowAnyException();
    }

    @Test
    void strongJwtSecretStarts() {
        assertThatCode(() -> new JwtUtil(STRONG, 3600_000, true)).doesNotThrowAnyException();
    }

    @Test
    void localAndTestProfilesMayRunWithoutASecret() {
        assertThatCode(() -> new JwtUtil("", 3600_000, false)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short-key", "0123456789012345678901234567890", "change_me_please_0123456789012345678901"})
    void weakEncryptionKeyFailsStartup(String key) {
        assertThatThrownBy(() -> new SecretCrypto(key, "", STRONG, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENCRYPTION_KEY");
    }

    @Test
    void encryptionKeyMustNotEqualTheJwtSecret() {
        assertThatThrownBy(() -> new SecretCrypto(STRONG, "", STRONG, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENCRYPTION_KEY");
    }

    @Test
    void weakPreviousEncryptionKeyFailsStartup() {
        assertThatThrownBy(() -> new SecretCrypto(STRONG + "-enc", "old", STRONG, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENCRYPTION_KEY_PREVIOUS");
    }

    @Test
    void rotationDecryptsWithThePreviousKey() {
        String oldKey = "old-encryption-key-0123456789-0123456789";
        String newKey = "new-encryption-key-0123456789-0123456789";
        new SecretCrypto(oldKey, "", STRONG, true);
        String stored = SecretCrypto.encrypt("sk-live-secret");

        new SecretCrypto(newKey, oldKey, STRONG, true);
        assertThat(SecretCrypto.decrypt(stored)).isEqualTo("sk-live-secret");
        assertThat(SecretCrypto.needsReencryption(stored)).isTrue();
        String rewritten = SecretCrypto.encrypt(SecretCrypto.decrypt(stored));
        assertThat(SecretCrypto.needsReencryption(rewritten)).isFalse();

        new SecretCrypto(newKey, "", STRONG, true);
        assertThat(SecretCrypto.decrypt(stored)).as("old ciphertext unreadable once the previous key is removed").isNull();
        assertThat(SecretCrypto.decrypt(rewritten)).isEqualTo("sk-live-secret");
    }
}
