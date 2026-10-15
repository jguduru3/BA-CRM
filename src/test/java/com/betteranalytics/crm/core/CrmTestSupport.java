package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.AppNotification;
import com.betteranalytics.crm.entity.AuditEvent;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AppNotificationRepository;
import com.betteranalytics.crm.repository.AuditEventRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared helpers for the CRM-core tests (same Spring context as every IntegrationTest). */
abstract class CrmTestSupport extends IntegrationTest {

    @Autowired protected AuditEventRepository auditEventRepository;
    @Autowired protected AppNotificationRepository notificationRepository;

    protected static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    protected long create(User u, String path, Map<String, Object> body) throws Exception {
        String res = postAs(u, path, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).path("id").asLong();
    }

    protected long lead(User u, Map<String, Object> body) throws Exception {
        Map<String, Object> b = new HashMap<>(body);
        b.putIfAbsent("name", "Lead " + System.nanoTime());
        return create(u, "/api/leads", b);
    }

    protected long lead(User u) throws Exception {
        return lead(u, Map.of());
    }

    protected long opp(User u, Map<String, Object> body) throws Exception {
        Map<String, Object> b = new HashMap<>(body);
        b.putIfAbsent("name", "Deal " + System.nanoTime());
        return create(u, "/api/opportunities", b);
    }

    protected long company(User u, Map<String, Object> body) throws Exception {
        Map<String, Object> b = new HashMap<>(body);
        b.putIfAbsent("name", "Acme " + System.nanoTime());
        return create(u, "/api/companies", b);
    }

    protected long contact(User u, Map<String, Object> body) throws Exception {
        Map<String, Object> b = new HashMap<>(body);
        b.putIfAbsent("name", "Pat " + System.nanoTime());
        return create(u, "/api/contacts", b);
    }

    protected long task(User u, Map<String, Object> body) throws Exception {
        Map<String, Object> b = new HashMap<>(body);
        b.putIfAbsent("title", "Call back " + System.nanoTime());
        return create(u, "/api/tasks", b);
    }

    protected long activity(User u, Map<String, Object> body) throws Exception {
        Map<String, Object> b = new HashMap<>(body);
        b.putIfAbsent("type", "call");
        return create(u, "/api/activities", b);
    }

    /** GET → 200 → JSON, decoded as UTF-8 (masked values contain non-ASCII bullets). */
    protected JsonNode getJson(User u, String url) throws Exception {
        String s = getAs(u, url).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return s.isEmpty() ? json.nullNode() : json.readTree(s);
    }

    protected List<AuditEvent> audits(Long orgId, String action) {
        return auditEventRepository.findByOrganizationIdAndAction(orgId, action);
    }

    protected List<AppNotification> notificationsOf(User u) {
        return notificationRepository.findByUserIdAndOrganizationIdOrderByReadAscCreatedAtDesc(u.getId(), u.getOrganizationId());
    }

    /** Runs a streamed (StreamingResponseBody) GET to completion and returns the raw response. */
    protected MvcResult stream(User u, String url) throws Exception {
        MvcResult started = mvc.perform(as(u, get(url))).andReturn();
        if (started.getRequest().isAsyncStarted()) {
            return mvc.perform(asyncDispatch(started)).andReturn();
        }
        return started;
    }
}
