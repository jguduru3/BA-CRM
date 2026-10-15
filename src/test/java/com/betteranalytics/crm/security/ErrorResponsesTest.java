package com.betteranalytics.crm.security;

import com.betteranalytics.crm.support.IntegrationTest;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Error responses never leak internals — including those produced outside Spring MVC. */
class ErrorResponsesTest extends IntegrationTest {

    @Test
    void firewallRejectionsUseTheStandardJsonBody() throws Exception {
        for (URI uri : new URI[]{
                new URI("/api/leads/%2e%2e/users"),
                new URI("/api/leads;jsessionid=abc"),
                new URI("/api/leads/%252e%252e/x")}) {
            String body = mvc.perform(get(uri))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                    .andExpect(jsonPath("$.error").isString())
                    .andExpect(jsonPath("$.requestId").isString())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("RequestRejectedException").doesNotContain("%2e").doesNotContain("jsessionid");
        }
    }

    @Test
    void springErrorPageNeverReturnsAStackTraceOrMessage() throws Exception {
        String body = mvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/leads")
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION,
                                new IllegalStateException("db password is hunter2 at org.example.Secret")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("hunter2").doesNotContain("org.example").doesNotContain("IllegalStateException");
    }

    @Test
    void unknownEndpointIsAJson404ForSignedInUsers() throws Exception {
        var u = user(newOrg(), com.betteranalytics.crm.entity.Role.SUPER_ADMIN);
        getAs(u, "/api/definitely-not-an-endpoint")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }
}
