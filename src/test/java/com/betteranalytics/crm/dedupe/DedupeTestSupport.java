package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.entity.AuditEvent;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AuditEventRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared helpers for the data-quality tests. */
abstract class DedupeTestSupport extends IntegrationTest {

    @Autowired protected AuditEventRepository auditEventRepository;
    @Autowired protected JdbcTemplate jdbc;

    protected static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    /** POST that must succeed (201), bypassing duplicate warnings so test setup is never blocked. */
    protected long create(User u, String path, Map<String, Object> body) throws Exception {
        return idOf(postAs(u, path + "?allowDuplicate=true", body).andExpect(status().isCreated()));
    }

    protected ResultActions postWithHeader(User u, String url, Object body, String header, String value) throws Exception {
        return mvc.perform(as(u, post(url)).header(header, value)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    protected JsonNode ok(ResultActions r) throws Exception {
        return body(r.andExpect(status().isOk()));
    }

    protected List<AuditEvent> audits(Long orgId, String action) {
        return auditEventRepository.findByOrganizationIdAndAction(orgId, action);
    }
}
