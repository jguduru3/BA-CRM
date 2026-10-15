package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PATCH-style updates (PUT and PATCH share one handler): fields the client didn't
 * send are never reset; an explicit null clears an optional field. Also covers
 * optimistic locking and URL-field validation.
 */
class PartialUpdateTest extends CrmTestSupport {

    @Test
    void leadUpdateKeepsUntouchedFields() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long id = lead(admin, m("name", "Big Co lead", "email", "a@b.example", "phone", "123", "value", 5000,
                "industry", "Retail", "priority", "High", "notes", "keep me"));
        putAs(admin, "/api/leads/" + id, Map.of("stage", "Contacted")).andExpect(status().isOk());
        patchAs(admin, "/api/leads/" + id, Map.of("geography", "Pune")).andExpect(status().isOk());

        JsonNode l = getJson(admin, "/api/leads/" + id);
        assertThat(l.path("stage").asText()).isEqualTo("Contacted");
        assertThat(l.path("geography").asText()).isEqualTo("Pune");
        assertThat(l.path("email").asText()).isEqualTo("a@b.example");
        assertThat(l.path("phone").asText()).isEqualTo("123");
        assertThat(l.path("value").asLong()).isEqualTo(5000);
        assertThat(l.path("industry").asText()).isEqualTo("Retail");
        assertThat(l.path("priority").asText()).isEqualTo("High");
        assertThat(l.path("notes").asText()).isEqualTo("keep me");
        assertThat(l.path("ownerId").asLong()).isEqualTo(admin.getId());

        // explicit null clears an optional field, but can't blank a required one
        patchAs(admin, "/api/leads/" + id, m("notes", null, "priority", null)).andExpect(status().isOk());
        l = getJson(admin, "/api/leads/" + id);
        assertThat(l.path("notes").isNull()).isTrue();
        assertThat(l.path("priority").asText()).isEqualTo("High");
    }

    @Test
    void opportunityUpdateKeepsValueProbabilityAndStage() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        long id = opp(rep, m("value", 250_000, "stage", "Negotiation", "notes", "n", "competitors", "X Corp"));
        patchAs(manager, "/api/opportunities/" + id, Map.of("probability", 75)).andExpect(status().isOk());

        // a rep renaming the deal must not reset value/stage/probability to the entity defaults
        // (that used to happen — and then failed with 403 because "probability changed")
        putAs(rep, "/api/opportunities/" + id, Map.of("name", "Renamed")).andExpect(status().isOk());
        JsonNode o = getJson(manager, "/api/opportunities/" + id);
        assertThat(o.path("name").asText()).isEqualTo("Renamed");
        assertThat(o.path("value").asLong()).isEqualTo(250_000);
        assertThat(o.path("stage").asText()).isEqualTo("Negotiation");
        assertThat(o.path("probability").asInt()).isEqualTo(75);
        assertThat(o.path("competitors").asText()).isEqualTo("X Corp");
        assertThat(o.path("notes").asText()).isEqualTo("n");
    }

    @Test
    void companyUpdateKeepsUntouchedFields() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long id = company(admin, m("industry", "Steel", "revenue", 42, "status", "Customer", "website", "acme.example"));
        patchAs(admin, "/api/companies/" + id, Map.of("location", "Chennai")).andExpect(status().isOk());
        putAs(admin, "/api/companies/" + id, Map.of("phone", "999")).andExpect(status().isOk());
        JsonNode c = getJson(admin, "/api/companies/" + id);
        assertThat(c.path("location").asText()).isEqualTo("Chennai");
        assertThat(c.path("phone").asText()).isEqualTo("999");
        assertThat(c.path("industry").asText()).isEqualTo("Steel");
        assertThat(c.path("revenue").asLong()).isEqualTo(42);
        assertThat(c.path("status").asText()).isEqualTo("Customer");
        assertThat(c.path("website").asText()).isEqualTo("https://acme.example");
    }

    @Test
    void contactUpdateKeepsUntouchedFields() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long companyId = company(admin, Map.of());
        long id = contact(admin, m("email", "c@d.example", "title", "CTO", "companyId", companyId,
                "linkedin", "https://www.linkedin.com/in/pat", "status", "Active"));
        putAs(admin, "/api/contacts/" + id, Map.of("phone", "777")).andExpect(status().isOk());
        JsonNode c = getJson(admin, "/api/contacts/" + id);
        assertThat(c.path("phone").asText()).isEqualTo("777");
        assertThat(c.path("email").asText()).isEqualTo("c@d.example");
        assertThat(c.path("title").asText()).isEqualTo("CTO");
        assertThat(c.path("companyId").asLong()).isEqualTo(companyId);
        assertThat(c.path("linkedin").asText()).isEqualTo("https://www.linkedin.com/in/pat");
    }

    @Test
    void taskUpdateKeepsUntouchedFields() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long leadId = lead(admin);
        long id = task(admin, m("priority", "High", "status", "In Progress", "dueDate", "2030-01-15",
                "relatedType", "Lead", "relatedId", leadId));
        patchAs(admin, "/api/tasks/" + id, Map.of("title", "New title")).andExpect(status().isOk());
        JsonNode t = getJson(admin, "/api/tasks/" + id);
        assertThat(t.path("title").asText()).isEqualTo("New title");
        assertThat(t.path("priority").asText()).isEqualTo("High");
        assertThat(t.path("status").asText()).isEqualTo("In Progress");
        assertThat(t.path("dueDate").asText()).isEqualTo("2030-01-15");
        assertThat(t.path("relatedType").asText()).isEqualTo("Lead");
        assertThat(t.path("relatedId").asLong()).isEqualTo(leadId);

        patchAs(admin, "/api/tasks/" + id, Map.of("status", "Done")).andExpect(status().isOk());
        assertThat(getJson(admin, "/api/tasks/" + id).path("completedAt").isNull()).isFalse();
    }

    @Test
    void badTypesAre400NotSilentlyIgnored() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long id = lead(admin);
        patchAs(admin, "/api/leads/" + id, Map.of("value", "lots")).andExpect(status().isBadRequest());
        patchAs(admin, "/api/leads/" + id, Map.of("followUpAt", "next tuesday")).andExpect(status().isBadRequest());
        patchAs(admin, "/api/leads/" + id, Map.of("name", " ")).andExpect(status().isBadRequest());
    }

    // ── optimistic locking ──────────────────────────────────────────────────────

    @Test
    void staleVersionIs409AndMissingVersionIsLastWriteWins() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long leadId = lead(admin);
        JsonNode loaded = getJson(admin, "/api/leads/" + leadId);
        long v0 = loaded.path("version").asLong();

        long v1 = body(patchAs(admin, "/api/leads/" + leadId, m("notes", "first", "version", v0))
                .andExpect(status().isOk())).path("version").asLong();
        assertThat(v1).isGreaterThan(v0);
        // a second client still holding v0
        patchAs(admin, "/api/leads/" + leadId, m("notes", "stale", "version", v0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_UPDATE"));
        // no version → last write wins
        patchAs(admin, "/api/leads/" + leadId, Map.of("notes", "lww")).andExpect(status().isOk());
        assertThat(getJson(admin, "/api/leads/" + leadId).path("notes").asText()).isEqualTo("lww");

        long oppId = opp(admin, Map.of());
        long ov = getJson(admin, "/api/opportunities/" + oppId).path("version").asLong();
        putAs(admin, "/api/opportunities/" + oppId, m("notes", "a", "version", ov)).andExpect(status().isOk());
        putAs(admin, "/api/opportunities/" + oppId, m("notes", "b", "version", ov))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_UPDATE"));
    }

    @Test
    void claimingBumpsTheVersionSoInFlightEditsConflict() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        long leadId = lead(manager);
        patchAs(manager, "/api/leads/" + leadId, m("ownerId", null)).andExpect(status().isOk());
        long v = getJson(manager, "/api/leads/" + leadId).path("version").asLong();
        postAs(rep, "/api/leads/" + leadId + "/claim", Map.of()).andExpect(status().isOk());
        patchAs(manager, "/api/leads/" + leadId, m("notes", "x", "version", v)).andExpect(status().isConflict());
    }

    // ── URL fields ──────────────────────────────────────────────────────────────

    @Test
    void unsafeLinksAreRejected() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        postAs(admin, "/api/companies", m("name", "X", "website", "javascript:alert(1)")).andExpect(status().isBadRequest());
        postAs(admin, "/api/companies", m("name", "X", "website", "data:text/html,<script>1</script>")).andExpect(status().isBadRequest());
        long companyId = company(admin, m("website", "acme.example"));
        putAs(admin, "/api/companies/" + companyId, m("website", "JaVaScRiPt:alert(1)")).andExpect(status().isBadRequest());
        assertThat(getJson(admin, "/api/companies/" + companyId).path("website").asText()).isEqualTo("https://acme.example");

        postAs(admin, "/api/contacts", m("name", "P", "linkedin", "javascript:alert(1)")).andExpect(status().isBadRequest());
        long contactId = contact(admin, m("linkedin", "linkedin.com/in/pat"));
        assertThat(getJson(admin, "/api/contacts/" + contactId).path("linkedin").asText()).isEqualTo("https://linkedin.com/in/pat");
        patchAs(admin, "/api/contacts/" + contactId, m("linkedin", "vbscript:msgbox")).andExpect(status().isBadRequest());
        patchAs(admin, "/api/contacts/" + contactId, m("linkedin", "")).andExpect(status().isOk());
        assertThat(getJson(admin, "/api/contacts/" + contactId).path("linkedin").isNull()).isTrue();
    }
}
