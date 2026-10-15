package com.betteranalytics.crm.config.migration;

import com.betteranalytics.crm.security.SecretCrypto;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V50 — encrypt third-party secrets that are still stored in plaintext.
 *
 * EncryptedStringConverter encrypts on save, but rows written before it
 * existed (or before an entity column was wired to it) stay readable plaintext
 * until somebody edits them. This migration rewrites every non-empty value that
 * does not start with "enc:v1:" through {@link SecretCrypto}, i.e. AES-256-GCM
 * under ENCRYPTION_KEY — exactly what the converter would have stored.
 *
 * It is a Spring bean (Spring Boot hands JavaMigration beans to Flyway), and it
 * takes SecretCrypto as a constructor argument, so the encryption key is always
 * initialised before Flyway runs. Idempotent: encrypted values are skipped, so
 * {@link #encryptAll(Connection)} can be re-run safely (e.g. after restoring an
 * old backup) — see DEPLOYMENT.md.
 */
@Component
public class V50__Encrypt_plaintext_secrets extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V50__Encrypt_plaintext_secrets.class);

    /** table -> secret column; every table has an "id" primary key. */
    static final Map<String, String> SECRET_COLUMNS = new LinkedHashMap<>();
    static {
        SECRET_COLUMNS.put("whatsapp_accounts", "access_token");
        SECRET_COLUMNS.put("email_accounts", "smtp_password_encrypted");
        SECRET_COLUMNS.put("ai_automations", "api_key_encrypted");
        SECRET_COLUMNS.put("org_settings", "ai_api_key");
        SECRET_COLUMNS.put("webhook_subscriptions", "secret");
    }

    private static final String PREFIX = "enc:v1:";

    public V50__Encrypt_plaintext_secrets(SecretCrypto secretCrypto) {
        // The parameter only exists to make Spring initialise SecretCrypto (and its key) first.
        if (secretCrypto == null) throw new IllegalStateException("SecretCrypto must be initialised before migrations");
    }

    @Override
    public void migrate(Context context) throws Exception {
        int n = encryptAll(context.getConnection());
        if (n > 0) log.info("V50: encrypted {} plaintext secret value(s) at rest", n);
    }

    /** Encrypts every plaintext secret; returns how many values were rewritten. */
    public static int encryptAll(Connection c) throws SQLException {
        int total = 0;
        for (Map.Entry<String, String> e : SECRET_COLUMNS.entrySet()) {
            total += encryptColumn(c, e.getKey(), e.getValue());
        }
        return total;
    }

    /**
     * Key rotation: rewrites every value that is plaintext or still encrypted
     * under ENCRYPTION_KEY_PREVIOUS so it is encrypted under the current key.
     * Values that decrypt with neither key are left alone. Run by
     * SecretReencryptionRunner at start-up while ENCRYPTION_KEY_PREVIOUS is set.
     */
    public static int reencryptAll(Connection c) throws SQLException {
        int total = 0;
        for (Map.Entry<String, String> e : SECRET_COLUMNS.entrySet()) {
            total += rewrite(c, e.getKey(), e.getValue(), true);
        }
        return total;
    }

    private static int encryptColumn(Connection c, String table, String column) throws SQLException {
        return rewrite(c, table, column, false);
    }

    private static int rewrite(Connection c, String table, String column, boolean rotate) throws SQLException {
        // Identifiers come from the constant map above, never from input.
        List<Object[]> pending = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, " + column + " FROM " + table + " WHERE " + column + " IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String v = rs.getString(2);
                if (v == null || v.isEmpty()) continue;
                if (!v.startsWith(PREFIX)) {
                    pending.add(new Object[]{rs.getLong(1), v});
                } else if (rotate && SecretCrypto.needsReencryption(v)) {
                    String plain = SecretCrypto.decrypt(v);
                    if (plain != null) pending.add(new Object[]{rs.getLong(1), plain});
                }
            }
        }
        if (pending.isEmpty()) return 0;
        try (PreparedStatement up = c.prepareStatement(
                "UPDATE " + table + " SET " + column + " = ? WHERE id = ?")) {
            for (Object[] row : pending) {
                up.setString(1, SecretCrypto.encrypt((String) row[1]));
                up.setLong(2, (Long) row[0]);
                up.addBatch();
            }
            up.executeBatch();
        }
        return pending.size();
    }
}
