package com.betteranalytics.crm.support;

import com.betteranalytics.crm.entity.Organization;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.OrganizationRepository;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Base class for HTTP-level tests: boots the full application against an
 * in-memory H2 database (all Flyway migrations applied) and drives it through
 * MockMvc, so every filter, permission check and exception handler is real.
 *
 * Each test creates its own organization(s) and users via {@link #newOrg()} /
 * {@link #user(Long, Role)}, so tests never depend on each other's data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String PASSWORD = "Str0ngPassw0rd!";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected OrganizationRepository organizationRepository;
    @Autowired protected UserRepository userRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JwtUtil jwtUtil;

    private static String encodedPassword;

    protected Long newOrg() {
        Organization o = new Organization();
        String slug = "org-" + UUID.randomUUID().toString().substring(0, 8);
        o.setName("Test " + slug);
        o.setSlug(slug);
        o.setCreatedAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return organizationRepository.save(o).getId();
    }

    protected User user(Long orgId, Role role) {
        return user(orgId, role, null);
    }

    protected User user(Long orgId, Role role, Long reportsToId) {
        if (encodedPassword == null) encodedPassword = passwordEncoder.encode(PASSWORD);
        User u = new User();
        u.setOrganizationId(orgId);
        u.setName(role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6));
        u.setEmail(u.getName() + "@test.example");
        u.setPassword(encodedPassword);
        u.setRole(role);
        u.setReportsToId(reportsToId);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        return userRepository.save(u);
    }

    protected String token(User u) {
        return jwtUtil.generateToken(userRepository.findById(u.getId()).orElseThrow());
    }

    protected MockHttpServletRequestBuilder as(User u, MockHttpServletRequestBuilder req) {
        return req.header("Authorization", "Bearer " + token(u));
    }

    protected ResultActions getAs(User u, String url) throws Exception {
        return mvc.perform(as(u, get(url)));
    }

    protected ResultActions deleteAs(User u, String url) throws Exception {
        return mvc.perform(as(u, delete(url)));
    }

    protected ResultActions postAs(User u, String url, Object body) throws Exception {
        return mvc.perform(as(u, post(url)).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    protected ResultActions putAs(User u, String url, Object body) throws Exception {
        return mvc.perform(as(u, put(url)).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    protected ResultActions patchAs(User u, String url, Object body) throws Exception {
        return mvc.perform(as(u, patch(url)).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    protected JsonNode body(ResultActions r) throws Exception {
        String s = r.andReturn().getResponse().getContentAsString();
        return s.isEmpty() ? json.nullNode() : json.readTree(s);
    }

    protected long idOf(ResultActions r) throws Exception {
        return body(r).path("id").asLong();
    }

    protected String toJson(Object body) throws Exception {
        return body instanceof String s ? s : json.writeValueAsString(body);
    }
}
