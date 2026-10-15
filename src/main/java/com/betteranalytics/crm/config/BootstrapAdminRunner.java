package com.betteranalytics.crm.config;

import com.betteranalytics.crm.entity.AuditEvent;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AuditEventRepository;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.security.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * First-admin bootstrap. V1 seeds admin@betteranalytics.in with a password
 * hash that is published in this repository, and V13 forces a change on first
 * sign-in — but that still means "whoever signs in first owns the tenant".
 *
 * When BOOTSTRAP_ADMIN_PASSWORD is set, this runner replaces the seed hash with
 * that password at start-up, ONLY while the account still carries the original
 * V1 hash (so it never overwrites a password someone already chose, and is a
 * no-op on every later restart). must_change_password stays true, so the admin
 * still picks a personal password on first sign-in. Remove the variable after
 * the first successful sign-in. See DEPLOYMENT.md → "First admin login".
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    static final String SEED_ADMIN_EMAIL = "admin@betteranalytics.in";
    /** The bcrypt hash V1__init.sql seeds — public, so it must never survive into production. */
    static final String V1_SEED_HASH = "$2b$10$t.gq/UfxLzcHm7Ajl5hWFOezhj8v5Ytb70U/OM0a7lUqcxjWEPpFu";

    private final UserRepository userRepository;
    private final AuditEventRepository auditEventRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapPassword;

    public BootstrapAdminRunner(UserRepository userRepository, AuditEventRepository auditEventRepository,
                                PasswordEncoder passwordEncoder,
                                @Value("${app.bootstrap.admin-password:}") String bootstrapPassword) {
        this.userRepository = userRepository;
        this.auditEventRepository = auditEventRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        apply();
    }

    /** @return true when the seed admin's password was replaced. */
    boolean apply() {
        if (bootstrapPassword == null || bootstrapPassword.isBlank()) return false;
        User admin = userRepository.findByEmail(SEED_ADMIN_EMAIL).orElse(null);
        if (admin == null) {
            log.info("BOOTSTRAP_ADMIN_PASSWORD is set but {} does not exist; nothing to do", SEED_ADMIN_EMAIL);
            return false;
        }
        if (!V1_SEED_HASH.equals(admin.getPassword())) {
            log.info("BOOTSTRAP_ADMIN_PASSWORD ignored: {} already has its own password (remove the variable)",
                    SEED_ADMIN_EMAIL);
            return false;
        }
        String problem = PasswordPolicy.validate(bootstrapPassword, admin.getEmail(), admin.getName());
        if (problem != null) {
            log.error("BOOTSTRAP_ADMIN_PASSWORD rejected by the password policy ({}); the seed admin was NOT changed",
                    problem);
            return false;
        }
        admin.setPassword(passwordEncoder.encode(bootstrapPassword));
        admin.setMustChangePassword(true);
        admin.setFailedLoginCount(0);
        admin.setLockedUntil(null);
        admin.setTokenVersion(admin.getTokenVersion() + 1);
        admin.setUpdatedAt(Instant.now());
        userRepository.save(admin);

        AuditEvent e = new AuditEvent();
        e.setOrganizationId(admin.getOrganizationId());
        e.setUserId(admin.getId());
        e.setUserName("system");
        e.setAction("bootstrap_admin_password");
        e.setResource("users");
        e.setResourceId(String.valueOf(admin.getId()));
        e.setResourceName(admin.getEmail());
        e.setCreatedAt(Instant.now());
        auditEventRepository.save(e);
        log.warn("Seed admin {} password set from BOOTSTRAP_ADMIN_PASSWORD; a change is still required at first sign-in. "
                + "Remove BOOTSTRAP_ADMIN_PASSWORD from the environment now.", SEED_ADMIN_EMAIL);
        return true;
    }
}
