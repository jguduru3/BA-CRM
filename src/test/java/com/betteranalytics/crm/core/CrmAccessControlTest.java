package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.LeadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant isolation, feature permissions, Support read-only and field masks for
 * every CRM-core controller.
 */
class CrmAccessControlTest extends CrmTestSupport {

    @Autowired LeadRepository leadRepository;

    // ── tenant isolation: another org's ids are simply "not found" ───────────────

    @Test
    void otherOrganisationGets404OnEveryRecordType() throws Exception {
        Long orgA = newOrg();
        User admin = user(orgA, Role.SUPER_ADMIN);
        long leadId = lead(admin);
        long oppId = opp(admin, Map.of());
        long companyId = company(admin, Map.of());
        long contactId = contact(admin, Map.of());
        long taskId = task(admin, Map.of());
        long activityId = activity(admin, m("relatedType", "Lead", "relatedId", leadId));

        User intruder = user(newOrg(), Role.SUPER_ADMIN);
        for (String path : new String[]{"/api/leads/" + leadId, "/api/opportunities/" + oppId,
                "/api/companies/" + companyId, "/api/contacts/" + contactId, "/api/tasks/" + taskId}) {
            getAs(intruder, path).andExpect(status().isNotFound());
            putAs(intruder, path, Map.of("name", "pwned", "title", "pwned")).andExpect(status().isNotFound());
            patchAs(intruder, path, Map.of("name", "pwned", "title", "pwned")).andExpect(status().isNotFound());
            deleteAs(intruder, path).andExpect(status().isNotFound());
        }
        getAs(intruder, "/api/activities/" + activityId).andExpect(status().isNotFound());
        postAs(intruder, "/api/activities/" + activityId + "/correct", Map.of("subject", "x")).andExpect(status().isNotFound());
        postAs(intruder, "/api/leads/" + leadId + "/convert", Map.of()).andExpect(status().isNotFound());
        postAs(intruder, "/api/leads/" + leadId + "/claim", Map.of()).andExpect(status().isNotFound());

        // and nothing of org A leaks into org B's lists, search or reports
        assertThat(getJson(intruder, "/api/leads").path("count").asLong()).isZero();
        assertThat(getJson(intruder, "/api/companies").path("count").asLong()).isZero();
        assertThat(getJson(intruder, "/api/reports/dashboard").path("totalLeads").asLong()).isZero();
        // the record itself was untouched
        assertThat(getJson(admin, "/api/leads/" + leadId).path("name").asText()).doesNotContain("pwned");
    }

    // ── record scope + roles without the feature ───────────────────────────────

    @Test
    void recordScopeAndMissingFeatureGive403() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User otherRep = user(org, Role.EXECUTIVE);
        User otherManager = user(org, Role.MANAGER);
        User inside = user(org, Role.INSIDE_SALES);

        long leadId = lead(rep);
        long oppId = opp(rep, Map.of());
        long taskId = task(rep, Map.of());

        // OWN scope: another rep can't read, edit or delete
        getAs(otherRep, "/api/leads/" + leadId).andExpect(status().isForbidden());
        putAs(otherRep, "/api/leads/" + leadId, Map.of("notes", "x")).andExpect(status().isForbidden());
        getAs(otherRep, "/api/opportunities/" + oppId).andExpect(status().isForbidden());
        getAs(otherRep, "/api/tasks/" + taskId).andExpect(status().isForbidden());
        // reps can't delete at all, even their own records
        deleteAs(rep, "/api/leads/" + leadId).andExpect(status().isForbidden());
        deleteAs(rep, "/api/opportunities/" + oppId).andExpect(status().isForbidden());
        // TEAM scope: own manager yes, another team's manager no
        getAs(manager, "/api/leads/" + leadId).andExpect(status().isOk());
        getAs(otherManager, "/api/leads/" + leadId).andExpect(status().isForbidden());
        assertThat(getJson(otherManager, "/api/leads").path("count").asLong()).isZero();
        assertThat(getJson(manager, "/api/leads").path("count").asLong()).isEqualTo(1);
        // Inside Sales has no opportunity feature at all
        getAs(inside, "/api/opportunities/" + oppId).andExpect(status().isForbidden());
        postAs(inside, "/api/opportunities", Map.of("name", "x")).andExpect(status().isForbidden());
        // activities are append-only: there is no update or delete
        long actId = activity(rep, m("relatedType", "Lead", "relatedId", leadId));
        deleteAs(manager, "/api/activities/" + actId).andExpect(status().isMethodNotAllowed());
        putAs(manager, "/api/activities/" + actId, Map.of("subject", "x")).andExpect(status().isMethodNotAllowed());
    }

    // ── SUPPORT: read-only ──────────────────────────────────────────────────────

    @Test
    void supportCanReadButNeverWrite() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User support = user(org, Role.SUPPORT);
        long leadId = lead(admin);
        long oppId = opp(admin, Map.of());
        long companyId = company(admin, Map.of());
        long contactId = contact(admin, m("companyId", companyId));
        long taskId = task(admin, Map.of());
        long actId = activity(admin, m("relatedType", "Company", "relatedId", companyId));

        // reads that Support has (companies, contacts, activities — org-wide)
        getAs(support, "/api/companies/" + companyId).andExpect(status().isOk());
        getAs(support, "/api/contacts/" + contactId).andExpect(status().isOk());
        getAs(support, "/api/activities/" + actId).andExpect(status().isOk());
        assertThat(getJson(support, "/api/companies").path("count").asLong()).isEqualTo(1);
        assertThat(getJson(support, "/api/contacts").path("count").asLong()).isEqualTo(1);
        assertThat(getJson(support, "/api/activities").path("count").asLong()).isEqualTo(1);

        // every write is 403
        postAs(support, "/api/companies", Map.of("name", "x")).andExpect(status().isForbidden());
        putAs(support, "/api/companies/" + companyId, Map.of("name", "x")).andExpect(status().isForbidden());
        patchAs(support, "/api/companies/" + companyId, Map.of("name", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/companies/" + companyId).andExpect(status().isForbidden());
        postAs(support, "/api/contacts", Map.of("name", "x")).andExpect(status().isForbidden());
        putAs(support, "/api/contacts/" + contactId, Map.of("name", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/contacts/" + contactId).andExpect(status().isForbidden());
        postAs(support, "/api/activities", m("type", "call", "relatedType", "Company", "relatedId", companyId))
                .andExpect(status().isForbidden());
        postAs(support, "/api/activities/" + actId + "/correct", Map.of("subject", "x")).andExpect(status().isForbidden());
        postAs(support, "/api/leads", Map.of("name", "x")).andExpect(status().isForbidden());
        putAs(support, "/api/leads/" + leadId, Map.of("name", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/leads/" + leadId).andExpect(status().isForbidden());
        postAs(support, "/api/leads/" + leadId + "/claim", Map.of()).andExpect(status().isForbidden());
        postAs(support, "/api/leads/" + leadId + "/convert", Map.of()).andExpect(status().isForbidden());
        postAs(support, "/api/opportunities", Map.of("name", "x")).andExpect(status().isForbidden());
        putAs(support, "/api/opportunities/" + oppId, Map.of("name", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/opportunities/" + oppId).andExpect(status().isForbidden());
        postAs(support, "/api/tasks", Map.of("title", "x")).andExpect(status().isForbidden());
        putAs(support, "/api/tasks/" + taskId, Map.of("title", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/tasks/" + taskId).andExpect(status().isForbidden());
        putAs(support, "/api/pipeline-settings", Map.of("bigDealThreshold", 1)).andExpect(status().isForbidden());

        // Support is read-only org-wide (README role table): leads, deals and tasks are readable too.
        assertThat(getJson(support, "/api/leads").path("count").asLong()).isEqualTo(1);
        getAs(support, "/api/leads/" + leadId).andExpect(status().isOk());
        getAs(support, "/api/opportunities/" + oppId).andExpect(status().isOk());
        assertThat(getJson(support, "/api/tasks").path("count").asLong()).isEqualTo(1);
    }

    // ── field masks ─────────────────────────────────────────────────────────────

    @Test
    void insideSalesNeverSeesDealFinancials() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User inside = user(org, Role.INSIDE_SALES, manager.getId());
        long leadId = lead(inside, m("email", "buyer@client.example", "value", 900_000));
        JsonNode converted = body(postAs(inside, "/api/leads/" + leadId + "/convert", Map.of()).andExpect(status().isOk()));
        long oppId = converted.path("opportunity").path("id").asLong();
        assertThat(converted.path("opportunity").path("value").isNull()).isTrue();

        // a Manager who owns nothing still sets the PO status, so there's something to hide
        patchAs(manager, "/api/opportunities/" + oppId, Map.of("poStatus", "Received", "probability", 70))
                .andExpect(status().isOk());

        JsonNode seen = getJson(inside, "/api/opportunities/" + oppId);
        assertThat(seen.path("stage").asText()).isEqualTo("Qualification");
        assertThat(seen.path("value").isNull()).isTrue();
        assertThat(seen.path("probability").isNull()).isTrue();
        assertThat(seen.path("poStatus").isNull()).isTrue();
        JsonNode listed = getJson(inside, "/api/opportunities").path("data").get(0);
        assertThat(listed.path("value").isNull()).isTrue();
        assertThat(listed.path("probability").isNull()).isTrue();
        assertThat(listed.path("poStatus").isNull()).isTrue();
        // and the aggregates don't leak it either
        assertThat(getJson(inside, "/api/reports/dashboard").path("totalOppValue").isNull()).isTrue();
        // sorting by a hidden field would leak ordering
        getAs(inside, "/api/opportunities?sort=value").andExpect(status().isBadRequest());

        JsonNode full = getJson(manager, "/api/opportunities/" + oppId);
        assertThat(full.path("value").asLong()).isEqualTo(900_000);
        assertThat(full.path("poStatus").asText()).isEqualTo("Received");
    }

    @Test
    void leadContactInfoIsMaskedForInsideSalesAfterHandOff() throws Exception {
        Long org = newOrg();
        User inside = user(org, Role.INSIDE_SALES);
        long leadId = lead(inside, m("email", "buyer@client.example", "phone", "+911234"));
        assertThat(getJson(inside, "/api/leads/" + leadId).path("email").asText()).isEqualTo("buyer@client.example");

        postAs(inside, "/api/leads/" + leadId + "/convert", Map.of()).andExpect(status().isOk());

        JsonNode after = getJson(inside, "/api/leads/" + leadId);
        assertThat(after.path("email").asText()).isEqualTo(CrmMasks.MASK);
        assertThat(after.path("phone").asText()).isEqualTo(CrmMasks.MASK);
        assertThat(getJson(inside, "/api/leads").path("data").get(0).path("email").asText()).isEqualTo(CrmMasks.MASK);
        // searching by the hidden email can't be used as an oracle
        assertThat(getJson(inside, "/api/leads?search=buyer@client").path("count").asLong()).isZero();

        // saving the form back with the placeholder never overwrites the real value
        putAs(inside, "/api/leads/" + leadId, m("email", CrmMasks.MASK, "phone", CrmMasks.MASK, "notes", "called"))
                .andExpect(status().isOk());
        Lead stored = leadRepository.findById(leadId).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo("buyer@client.example");
        assertThat(stored.getPhone()).isEqualTo("+911234");
        assertThat(stored.getNotes()).isEqualTo("called");
    }

    @Test
    void activityBodiesOfOthersAreRestrictedForReps() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        long leadId = lead(rep);
        long actId = activity(manager, m("relatedType", "Lead", "relatedId", leadId, "subject", "pricing", "body", "secret"));
        getAs(manager, "/api/activities/" + actId).andExpect(jsonPath("$.body").value("secret"));
        // a rep can't see a manager's activity at all (OWN scope)
        getAs(rep, "/api/activities/" + actId).andExpect(status().isForbidden());
    }
}
