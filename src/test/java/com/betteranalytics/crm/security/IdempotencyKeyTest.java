package com.betteranalytics.crm.security;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.ContactCategoryRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Idempotency-Key on the normal (JWT) API: replay, mismatch, in-progress, expiry, what is not stored. */
class IdempotencyKeyTest extends IntegrationTest {

    @Autowired IdempotencyStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContactCategoryRepository categoryRepository;

    private ResultActions post(User u, String url, String body, String key) throws Exception {
        var req = as(u, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req.header(IdempotencyFilter.HEADER, key);
        return mvc.perform(req);
    }

    private static String categoryBody() {
        return "{\"name\":\"Cat " + UUID.randomUUID().toString().substring(0, 8) + "\"}";
    }

    private long categories(Long orgId) {
        return categoryRepository.findAll().stream().filter(c -> orgId.equals(c.getOrganizationId())).count();
    }

    @Test
    void retryWithTheSameKeyReplaysTheFirstResponse() throws Exception {
        User manager = user(newOrg(), Role.MANAGER);
        String key = UUID.randomUUID().toString();
        String body = categoryBody();

        MvcResult first = post(manager, "/api/contact-categories", body, key).andExpect(status().isCreated()).andReturn();
        assertThat(first.getResponse().getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();
        MvcResult second = post(manager, "/api/contact-categories", body, key).andExpect(status().isCreated()).andReturn();
        assertThat(second.getResponse().getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(second.getResponse().getContentType()).contains("application/json");
        assertThat(categories(manager.getOrganizationId())).isEqualTo(1);

        // Without a key the same body runs again (409: duplicate name) — the key is what makes retries safe.
        post(manager, "/api/contact-categories", body, null).andExpect(status().isConflict());

        // Keys are per credential: another user's identical key is a separate request.
        User other = user(manager.getOrganizationId(), Role.MANAGER);
        post(other, "/api/contact-categories", categoryBody(), key).andExpect(status().isCreated());
    }

    @Test
    void sameKeyWithADifferentRequestIs422() throws Exception {
        User manager = user(newOrg(), Role.MANAGER);
        String key = "key-" + UUID.randomUUID();
        post(manager, "/api/contact-categories", categoryBody(), key).andExpect(status().isCreated());
        JsonNode err = body(post(manager, "/api/contact-categories", categoryBody(), key)
                .andExpect(status().isUnprocessableEntity()));
        assertThat(err.path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(categories(manager.getOrganizationId())).isEqualTo(1);
    }

    @Test
    void aRetryWhileTheFirstIsStillRunningIs409() throws Exception {
        User manager = user(newOrg(), Role.MANAGER);
        String key = "key-" + UUID.randomUUID();
        String body = categoryBody();
        String hash = IdempotencyFilter.requestHash("POST", "/api/contact-categories", null,
                body.getBytes(StandardCharsets.UTF_8));
        assertThat(store.begin("user:" + manager.getId(), key, hash).kind()).isEqualTo(IdempotencyStore.Kind.NEW);

        MvcResult r = post(manager, "/api/contact-categories", body, key).andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(r.getResponse().getContentAsString()).path("code").asText()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("1");
        assertThat(categories(manager.getOrganizationId())).isZero();
    }

    @Test
    void keysExpireAfter24HoursAndArePruned() throws Exception {
        User manager = user(newOrg(), Role.MANAGER);
        String key = "key-" + UUID.randomUUID();
        String body = categoryBody();
        post(manager, "/api/contact-categories", body, key).andExpect(status().isCreated());

        LocalDateTime old = LocalDateTime.ofInstant(Instant.now().minusSeconds(25 * 3600), ZoneOffset.UTC);
        jdbc.update("UPDATE idempotency_keys SET created_at = ? WHERE idem_key = ?", old, key);
        // Expired (even before pruning): treated as a new key → the request runs again (duplicate name → 409).
        MvcResult again = post(manager, "/api/contact-categories", body, key).andExpect(status().isConflict()).andReturn();
        assertThat(again.getResponse().getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();

        jdbc.update("UPDATE idempotency_keys SET created_at = ? WHERE idem_key = ?", old, key);
        store.pruneExpired();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idem_key = ?", Integer.class, key)).isZero();
    }

    @Test
    void validationAndPermissionFailuresAreNotStored() throws Exception {
        User manager = user(newOrg(), Role.MANAGER);
        String key = "key-" + UUID.randomUUID();
        post(manager, "/api/contact-categories", "{\"name\":\"\"}", key).andExpect(status().isBadRequest());
        // Released: the corrected request may reuse the key.
        post(manager, "/api/contact-categories", categoryBody(), key).andExpect(status().isCreated());

        User rep = user(manager.getOrganizationId(), Role.EXECUTIVE);
        String repKey = "key-" + UUID.randomUUID();
        String body = categoryBody();
        post(rep, "/api/contact-categories", body, repKey).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idem_key = ?", Integer.class, repKey)).isZero();

        // Filter-level rejection (no credential) never reaches the store.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/contact-categories")
                .contentType(MediaType.APPLICATION_JSON).content(body).header(IdempotencyFilter.HEADER, repKey))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idem_key = ?", Integer.class, repKey)).isZero();
    }

    @Test
    void malformedKeysAreRejectedAndGetIsIgnored() throws Exception {
        User manager = user(newOrg(), Role.MANAGER);
        post(manager, "/api/contact-categories", categoryBody(), "x".repeat(256)).andExpect(status().isBadRequest());
        post(manager, "/api/contact-categories", categoryBody(), "").andExpect(status().isBadRequest());
        mvc.perform(as(manager, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/contact-categories"))
                .contentType(MediaType.APPLICATION_JSON).content(categoryBody())
                .header(IdempotencyFilter.HEADER, "a").header(IdempotencyFilter.HEADER, "b"))
                .andExpect(status().isBadRequest());
        // sf-string form is accepted
        post(manager, "/api/contact-categories", categoryBody(), "\"quoted-" + UUID.randomUUID() + "\"").andExpect(status().isCreated());
        mvc.perform(as(manager, get("/api/contact-categories")).header(IdempotencyFilter.HEADER, "k"))
                .andExpect(status().isOk());
    }
}
