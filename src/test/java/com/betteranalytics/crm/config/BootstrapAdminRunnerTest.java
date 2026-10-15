package com.betteranalytics.crm.config;

import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AuditEventRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BOOTSTRAP_ADMIN_PASSWORD replaces the published V1 seed hash, once, keeping the forced change. */
class BootstrapAdminRunnerTest extends IntegrationTest {

    private static final String BOOT_PW = "Kestrel-Harbor-5521";

    @Autowired AuditEventRepository auditEventRepository;

    private User seed;
    private String originalHash;
    private boolean originalMustChange;

    @BeforeEach
    void remember() {
        seed = userRepository.findByEmail(BootstrapAdminRunner.SEED_ADMIN_EMAIL).orElseThrow();
        originalHash = seed.getPassword();
        originalMustChange = seed.isMustChangePassword();
        seed.setPassword(BootstrapAdminRunner.V1_SEED_HASH);
        userRepository.save(seed);
    }

    @AfterEach
    void restore() {
        User u = userRepository.findById(seed.getId()).orElseThrow();
        u.setPassword(originalHash);
        u.setMustChangePassword(originalMustChange);
        userRepository.save(u);
    }

    private BootstrapAdminRunner runner(String pw) {
        return new BootstrapAdminRunner(userRepository, auditEventRepository, passwordEncoder, pw);
    }

    @Test
    void setsThePasswordOnlyWhileTheSeedHashIsPresent() throws Exception {
        assertThat(runner(BOOT_PW).apply()).isTrue();
        User u = userRepository.findById(seed.getId()).orElseThrow();
        assertThat(u.getPassword()).isNotEqualTo(BootstrapAdminRunner.V1_SEED_HASH);
        assertThat(passwordEncoder.matches(BOOT_PW, u.getPassword())).isTrue();
        assertThat(u.isMustChangePassword()).as("a change is still forced at first sign-in").isTrue();

        mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("10.250.0.9"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", BootstrapAdminRunner.SEED_ADMIN_EMAIL, "password", BOOT_PW))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // second start-up (or a different value later) never overwrites a real password
        assertThat(runner("Another-Strong-7781").apply()).isFalse();
        assertThat(passwordEncoder.matches(BOOT_PW, userRepository.findById(seed.getId()).orElseThrow().getPassword())).isTrue();
    }

    @Test
    void doesNothingWithoutTheVariableOrWithAWeakPassword() {
        assertThat(runner("").apply()).isFalse();
        assertThat(runner(null).apply()).isFalse();
        assertThat(runner("password123").apply()).isFalse();
        assertThat(runner("Admin-Secure-2291").apply()).as("contains the admin's name").isFalse();
        assertThat(userRepository.findById(seed.getId()).orElseThrow().getPassword())
                .isEqualTo(BootstrapAdminRunner.V1_SEED_HASH);
    }
}
