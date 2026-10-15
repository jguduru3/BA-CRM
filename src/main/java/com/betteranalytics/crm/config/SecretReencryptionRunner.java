package com.betteranalytics.crm.config;

import com.betteranalytics.crm.config.migration.V50__Encrypt_plaintext_secrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * ENCRYPTION_KEY rotation, step 2 of DEPLOYMENT.md §4: while
 * ENCRYPTION_KEY_PREVIOUS is set, every start-up re-encrypts stored secrets
 * that are still under the previous key (or plaintext) with the current key.
 * Idempotent and cheap once done — remove ENCRYPTION_KEY_PREVIOUS afterwards.
 * Safe with several replicas: each value is rewritten from the same plaintext.
 */
@Component
public class SecretReencryptionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretReencryptionRunner.class);

    private final DataSource dataSource;
    private final String previousKey;

    public SecretReencryptionRunner(DataSource dataSource,
                                    @Value("${app.encryption-key-previous:}") String previousKey) {
        this.dataSource = dataSource;
        this.previousKey = previousKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (previousKey == null || previousKey.isBlank()) return;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            int n = V50__Encrypt_plaintext_secrets.reencryptAll(c);
            log.warn("ENCRYPTION_KEY_PREVIOUS is set: re-encrypted {} stored secret(s) under the current key. "
                    + "Remove ENCRYPTION_KEY_PREVIOUS once every replica runs with the new key.", n);
        } catch (Exception e) {
            log.error("Secret re-encryption failed; values under the previous key stay readable while "
                    + "ENCRYPTION_KEY_PREVIOUS is set: {}", e.getMessage());
        }
    }
}
