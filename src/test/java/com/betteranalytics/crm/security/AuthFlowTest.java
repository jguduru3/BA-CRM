package com.betteranalytics.crm.security;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthFlowTest extends IntegrationTest {

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password))));
    }

    @Test
    void unauthenticatedRequestsGetJson401() throws Exception {
        mvc.perform(get("/api/leads"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void loginIsCaseInsensitiveAndReturnsToken() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        login(u.getEmail().toUpperCase(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.password").doesNotExist());
    }

    @Test
    void unknownEmailAndWrongPasswordLookIdentical() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        String a = login("nobody-" + u.getEmail(), "whatever123").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String b = login(u.getEmail(), "wrong-password-1").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void accountLocksAfterRepeatedFailures() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        for (int i = 0; i < 5; i++) login(u.getEmail(), "wrong-password-" + i);
        login(u.getEmail(), PASSWORD).andExpect(status().is(423)).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void mustChangePasswordGatesEverythingElse() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        u.setMustChangePassword(true);
        userRepository.save(u);

        getAs(u, "/api/leads").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        getAs(u, "/api/auth/me").andExpect(status().isOk());

        String newToken = body(postAs(u, "/api/auth/change-password",
                Map.of("currentPassword", PASSWORD, "newPassword", "BrandNewPassw0rd"))
                .andExpect(status().isOk())).path("token").asText();

        mvc.perform(get("/api/leads").header("Authorization", "Bearer " + newToken)).andExpect(status().isOk());
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndPolicy() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        postAs(u, "/api/auth/change-password", Map.of("currentPassword", "nope", "newPassword", "BrandNewPassw0rd"))
                .andExpect(status().isBadRequest());
        postAs(u, "/api/auth/change-password", Map.of("currentPassword", PASSWORD, "newPassword", "short1"))
                .andExpect(status().isBadRequest());
        postAs(u, "/api/auth/change-password", Map.of("currentPassword", PASSWORD, "newPassword", "onlyletterslong"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordChangeRevokesExistingTokens() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        String oldToken = token(u);
        postAs(u, "/api/auth/change-password", Map.of("currentPassword", PASSWORD, "newPassword", "BrandNewPassw0rd"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllRevokesTokens() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        String t = token(u);
        mvc.perform(post("/api/auth/logout-all").header("Authorization", "Bearer " + t)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatedUserTokenStopsWorking() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        String t = token(u);
        u.setActive(false);
        userRepository.save(u);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTokenIs401() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void errorsNeverLeakInternals() throws Exception {
        User u = user(newOrg(), Role.SUPER_ADMIN);
        mvc.perform(post("/api/leads").header("Authorization", "Bearer " + token(u))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }
}
