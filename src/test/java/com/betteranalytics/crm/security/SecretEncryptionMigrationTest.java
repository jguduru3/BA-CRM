package com.betteranalytics.crm.security;

import com.betteranalytics.crm.config.migration.V50__Encrypt_plaintext_secrets;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V50 encrypts plaintext secrets in place (H2), and is idempotent. */
class SecretEncryptionMigrationTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void migrationRanAndIsRegisteredAsVersion50() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '50' AND \"success\" = TRUE", Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void encryptsEveryPlaintextSecretAndSkipsEncryptedOnes() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        String tag = UUID.randomUUID().toString().substring(0, 8);

        jdbc.update("INSERT INTO whatsapp_accounts (organization_id, phone_number_id, access_token) VALUES (?, ?, ?)",
                org, "pn-" + tag, "wa-token-" + tag);
        jdbc.update("INSERT INTO email_accounts (organization_id, user_id, email_address, smtp_password_encrypted) VALUES (?, ?, ?, ?)",
                org, admin.getId(), tag + "@mail.example", "smtp-pass-" + tag);
        jdbc.update("INSERT INTO ai_automations (organization_id, name, trigger_type, action_type, api_key_encrypted, created_by) "
                + "VALUES (?, ?, 'manual', 'summarize', ?, ?)", org, "auto-" + tag, "sk-ai-" + tag, admin.getId());
        jdbc.update("INSERT INTO org_settings (organization_id, ai_api_key) VALUES (?, ?)", org, "sk-org-" + tag);
        jdbc.update("INSERT INTO webhook_subscriptions (organization_id, name, url, secret, events, created_by) "
                + "VALUES (?, 'hook', 'https://example.com/h', ?, '*', ?)", org, "whsec_" + tag, admin.getId());
        String alreadyEncrypted = SecretCrypto.encrypt("already-" + tag);
        jdbc.update("INSERT INTO webhook_subscriptions (organization_id, name, url, secret, events, created_by) "
                + "VALUES (?, 'hook2', 'https://example.com/h2', ?, '*', ?)", org, alreadyEncrypted, admin.getId());

        int first;
        try (Connection c = dataSource.getConnection()) {
            first = V50__Encrypt_plaintext_secrets.encryptAll(c);
        }
        assertThat(first).isGreaterThanOrEqualTo(5);

        assertEncrypted("SELECT access_token FROM whatsapp_accounts WHERE organization_id = ?", org, "wa-token-" + tag);
        assertEncrypted("SELECT smtp_password_encrypted FROM email_accounts WHERE organization_id = ?", org, "smtp-pass-" + tag);
        assertEncrypted("SELECT api_key_encrypted FROM ai_automations WHERE organization_id = ?", org, "sk-ai-" + tag);
        assertEncrypted("SELECT ai_api_key FROM org_settings WHERE organization_id = ?", org, "sk-org-" + tag);
        assertEncrypted("SELECT secret FROM webhook_subscriptions WHERE organization_id = ? AND name = 'hook'", org, "whsec_" + tag);
        assertThat(jdbc.queryForObject("SELECT secret FROM webhook_subscriptions WHERE organization_id = ? AND name = 'hook2'",
                String.class, org)).as("already-encrypted values are left untouched").isEqualTo(alreadyEncrypted);

        String before = jdbc.queryForObject("SELECT access_token FROM whatsapp_accounts WHERE organization_id = ?", String.class, org);
        try (Connection c = dataSource.getConnection()) {
            assertThat(V50__Encrypt_plaintext_secrets.encryptAll(c)).as("idempotent").isZero();
        }
        assertThat(jdbc.queryForObject("SELECT access_token FROM whatsapp_accounts WHERE organization_id = ?", String.class, org))
                .isEqualTo(before);
    }

    @Test
    void rotationReencryptsValuesStillUnderThePreviousKey() throws Exception {
        String testKey = "test-encryption-key-0123456789-0123456789";
        String testJwt = "test-secret-test-secret-test-secret-0123456789";
        String newKey = "rotated-encryption-key-0123456789-01234567";
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        String underOld = SecretCrypto.encrypt("whsec_rotate_me");
        jdbc.update("INSERT INTO webhook_subscriptions (organization_id, name, url, secret, events, created_by) "
                + "VALUES (?, 'rot', 'https://example.com/r', ?, '*', ?)", org, underOld, admin.getId());
        try {
            new SecretCrypto(newKey, testKey, testJwt, false);
            try (Connection c = dataSource.getConnection()) {
                assertThat(V50__Encrypt_plaintext_secrets.reencryptAll(c)).isGreaterThanOrEqualTo(1);
            }
            String rotated = jdbc.queryForObject(
                    "SELECT secret FROM webhook_subscriptions WHERE organization_id = ? AND name = 'rot'", String.class, org);
            assertThat(rotated).isNotEqualTo(underOld).startsWith("enc:v1:");
            new SecretCrypto(newKey, "", testJwt, false);
            assertThat(SecretCrypto.decrypt(rotated)).as("readable with the new key alone").isEqualTo("whsec_rotate_me");
        } finally {
            // rotate everything back so other tests' rows stay readable under the test key
            new SecretCrypto(testKey, newKey, testJwt, false);
            try (Connection c = dataSource.getConnection()) {
                V50__Encrypt_plaintext_secrets.reencryptAll(c);
            }
            new SecretCrypto(testKey, "", testJwt, false);
            jdbc.update("DELETE FROM webhook_subscriptions WHERE organization_id = ?", org);
        }
    }

    private void assertEncrypted(String sql, Long org, String plaintext) {
        String stored = jdbc.queryForObject(sql, String.class, org);
        assertThat(stored).startsWith("enc:v1:").doesNotContain(plaintext);
        assertThat(SecretCrypto.decrypt(stored)).isEqualTo(plaintext);
    }
}
