package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.dedupe.migration.V66__Backfill_match_keys;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Match keys, matching rules and the WARN / BLOCK / ALLOW duplicate rules on create and edit. */
class DuplicateRulesTest extends DedupeTestSupport {

    @Autowired DataSource dataSource;

    // ── match keys ──────────────────────────────────────────────────────────────

    @Test
    void matchKeysAreMaintainedOnSaveAndNeverSerialized() throws Exception {
        User rep = user(newOrg(), Role.EXECUTIVE);
        JsonNode created = body(postAs(rep, "/api/leads",
                m("name", "  Pat O'Neil ", "email", " Pat@Acme.COM ", "phone", "+91 98765-43210"))
                .andExpect(status().isCreated()));
        long id = created.path("id").asLong();
        assertThat(created.has("emailNormalized")).isFalse();
        assertThat(created.has("nameKey")).isFalse();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT email_normalized, phone_normalized, domain_normalized, name_key, name_phonetic FROM leads WHERE id = ?", id);
        assertThat(row.get("email_normalized")).isEqualTo("pat@acme.com");
        assertThat(row.get("phone_normalized")).isEqualTo("+919876543210");
        assertThat(row.get("domain_normalized")).isEqualTo("acme.com");
        assertThat(row.get("name_key")).isEqualTo("pat o neil");
        assertThat(row.get("name_phonetic")).isNotNull();

        // updates refresh the keys too (Lead uses @DynamicUpdate)
        patchAs(rep, "/api/leads/" + id, m("email", "pat@globex.io")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT email_normalized FROM leads WHERE id = ?", String.class, id))
                .isEqualTo("pat@globex.io");
        assertThat(jdbc.queryForObject("SELECT domain_normalized FROM leads WHERE id = ?", String.class, id))
                .isEqualTo("globex.io");

        long company = create(rep, "/api/companies", m("name", "Initech Pvt Ltd", "website", "https://www.initech.com/x"));
        Map<String, Object> c = jdbc.queryForMap("SELECT domain_normalized, name_key FROM companies WHERE id = ?", company);
        assertThat(c.get("domain_normalized")).isEqualTo("initech.com");
        assertThat(c.get("name_key")).isEqualTo("initech");
    }

    @Test
    void backfillComputesKeysForExistingRows() throws Exception {
        User rep = user(newOrg(), Role.EXECUTIVE);
        long id = create(rep, "/api/contacts", m("name", "Zoë Quinn", "email", "ZQ@Example.org"));
        jdbc.update("UPDATE contacts SET email_normalized = NULL, name_key = NULL, name_phonetic = NULL WHERE id = ?", id);

        try (Connection conn = dataSource.getConnection()) {
            assertThat(V66__Backfill_match_keys.backfill(conn, "contacts", false)).isPositive();
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT email_normalized, name_key, name_phonetic FROM contacts WHERE id = ?", id);
        assertThat(row.get("email_normalized")).isEqualTo("zq@example.org");
        assertThat(row.get("name_key")).isEqualTo("zoe quinn");
        assertThat(row.get("name_phonetic")).isNotNull();
    }

    // ── WARN (default) ──────────────────────────────────────────────────────────

    @Test
    void warnOnCreateListsCandidatesAndCanBeBypassed() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long first = create(rep, "/api/leads", m("name", "Dana Scully", "email", "dana@fbi.gov"));

        postAs(rep, "/api/leads", m("name", "D. Scully", "email", " DANA@fbi.gov"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_WARNING"))
                .andExpect(jsonPath("$.canBypass").value(true))
                .andExpect(jsonPath("$.candidates[0].id").value(first))
                .andExpect(jsonPath("$.candidates[0].name").value("Dana Scully"))
                .andExpect(jsonPath("$.candidates[0].matchedOn[0]").value("email"))
                .andExpect(jsonPath("$.candidates[0].score").value(1.0))
                .andExpect(jsonPath("$.candidates[0].ownerId").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM leads WHERE organization_id = ?", Long.class, org)).isEqualTo(1);

        long viaParam = idOf(postAs(rep, "/api/leads?allowDuplicate=true", m("name", "Dana S", "email", "dana@fbi.gov"))
                .andExpect(status().isCreated()));
        long viaHeader = idOf(postWithHeader(rep, "/api/leads", m("name", "Dana Sc", "email", "dana@fbi.gov"),
                "X-Allow-Duplicate", "true").andExpect(status().isCreated()));
        var bypasses = audits(org, "duplicate_bypass");
        assertThat(bypasses).extracting(e -> e.getResourceId())
                .contains(String.valueOf(viaParam), String.valueOf(viaHeader));
        assertThat(bypasses.get(0).getAfterJson()).contains("\"candidates\"");
    }

    @Test
    void phoneAndFuzzyNameMatchesForContacts() throws Exception {
        User rep = user(newOrg(), Role.EXECUTIVE);
        long acme = create(rep, "/api/companies", m("name", "Acme"));
        long jonathan = create(rep, "/api/contacts", m("name", "Jonathan Smith", "companyId", acme));
        long phoneOwner = create(rep, "/api/contacts", m("name", "Lee Wong", "phone", "+44 20 7946 0958"));

        JsonNode typo = body(postAs(rep, "/api/contacts", m("name", "Jonathon Smith", "companyId", acme))
                .andExpect(status().isConflict()));
        assertThat(typo.path("candidates").get(0).path("id").asLong()).isEqualTo(jonathan);
        assertThat(typo.path("candidates").get(0).path("matchedOn").toString()).contains("name").contains("company");

        JsonNode initials = body(postAs(rep, "/api/contacts", m("name", "J. Smith")).andExpect(status().isConflict()));
        assertThat(initials.path("candidates").get(0).path("id").asLong()).isEqualTo(jonathan);

        JsonNode phone = body(postAs(rep, "/api/contacts", m("name", "Someone Else", "phone", "+442079460958"))
                .andExpect(status().isConflict()));
        assertThat(phone.path("candidates").get(0).path("id").asLong()).isEqualTo(phoneOwner);
        assertThat(phone.path("candidates").get(0).path("matchedOn").get(0).asText()).isEqualTo("phone");

        // a different person at the same company is not a duplicate
        postAs(rep, "/api/contacts", m("name", "Maria Garcia", "companyId", acme)).andExpect(status().isCreated());
    }

    @Test
    void companiesMatchOnDomainAndName() throws Exception {
        User rep = user(newOrg(), Role.EXECUTIVE);
        long acme = create(rep, "/api/companies", m("name", "Acme Industries", "website", "https://www.acme.com"));

        JsonNode domain = body(postAs(rep, "/api/companies", m("name", "ACME Holdings", "website", "acme.com/about"))
                .andExpect(status().isConflict()));
        assertThat(domain.path("candidates").get(0).path("id").asLong()).isEqualTo(acme);
        assertThat(domain.path("candidates").get(0).path("matchedOn").toString()).contains("domain");

        JsonNode name = body(postAs(rep, "/api/companies", m("name", "Acme Industries Pvt. Ltd.")).andExpect(status().isConflict()));
        assertThat(name.path("candidates").get(0).path("matchedOn").toString()).contains("name");

        // freemail is never a company domain
        create(rep, "/api/companies", m("name", "Foo Bakery", "email", "foo@gmail.com"));
        postAs(rep, "/api/companies", m("name", "Bar Plumbing", "email", "bar@gmail.com")).andExpect(status().isCreated());
    }

    // ── edit ────────────────────────────────────────────────────────────────────

    @Test
    void editsAreCheckedOnlyWhenMatchKeysChange() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        create(rep, "/api/leads", m("name", "Fox Mulder", "email", "fox@fbi.gov"));
        long other = create(rep, "/api/leads", m("name", "Walter Skinner", "email", "walter@fbi.gov"));

        patchAs(rep, "/api/leads/" + other, m("email", "fox@fbi.gov"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_WARNING"));
        assertThat(jdbc.queryForObject("SELECT email FROM leads WHERE id = ?", String.class, other)).isEqualTo("walter@fbi.gov");

        patchAs(rep, "/api/leads/" + other + "?allowDuplicate=true", m("email", "fox@fbi.gov")).andExpect(status().isOk());
        // now a known duplicate: editing a non-key field is not re-checked
        patchAs(rep, "/api/leads/" + other, m("notes", "met at HQ")).andExpect(status().isOk());

        long c1 = create(rep, "/api/companies", m("name", "Globex", "website", "globex.com"));
        long c2 = create(rep, "/api/companies", m("name", "Hooli"));
        putAs(rep, "/api/companies/" + c2, m("website", "https://globex.com")).andExpect(status().isConflict());
        putAs(rep, "/api/companies/" + c1, m("industry", "Energy")).andExpect(status().isOk());
    }

    // ── BLOCK / ALLOW / visibility ──────────────────────────────────────────────

    @Test
    void blockRuleRejectsWithoutBypassAndAllowSavesSilently() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User rep = user(org, Role.EXECUTIVE);
        create(rep, "/api/companies", m("name", "Umbrella", "website", "umbrella.com"));

        putAs(admin, "/api/duplicate-rules/company", m("actionOnCreate", "BLOCK")).andExpect(status().isOk());
        postAs(rep, "/api/companies?allowDuplicate=true", m("name", "Umbrella Corp", "website", "www.umbrella.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BLOCKED"))
                .andExpect(jsonPath("$.canBypass").value(false));

        putAs(admin, "/api/duplicate-rules/COMPANY", m("actionOnCreate", "ALLOW")).andExpect(status().isOk());
        postAs(rep, "/api/companies", m("name", "Umbrella Corp", "website", "www.umbrella.com")).andExpect(status().isCreated());

        putAs(admin, "/api/duplicate-rules/company", m("actionOnCreate", "WARN", "enabled", false)).andExpect(status().isOk());
        postAs(rep, "/api/companies", m("name", "Umbrella Inc", "website", "umbrella.com")).andExpect(status().isCreated());
        assertThat(audits(org, "duplicate_bypass")).isEmpty();
    }

    @Test
    void warningsOnlyShowRecordsTheCallerMayViewButBlockingSeesAll() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User manager = user(org, Role.MANAGER);
        User alice = user(org, Role.EXECUTIVE, manager.getId());
        User bob = user(org, Role.EXECUTIVE, manager.getId());
        create(alice, "/api/leads", m("name", "Secret Prospect", "email", "ceo@bigco.com"));

        // Bob cannot see Alice's lead, so there is nothing to warn him about
        postAs(bob, "/api/leads", m("name", "Big Co CEO", "email", "ceo@bigco.com")).andExpect(status().isCreated());
        // her manager can see both
        postAs(manager, "/api/leads", m("name", "CEO", "email", "ceo@bigco.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.candidates.length()").value(2));

        putAs(admin, "/api/duplicate-rules/lead", m("actionOnCreate", "BLOCK")).andExpect(status().isOk());
        User carol = user(org, Role.EXECUTIVE, manager.getId());
        postAs(carol, "/api/leads", m("name", "CEO again", "email", "ceo@bigco.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BLOCKED"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test
    void onlySuperAdminAndVpChangeRules() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User vp = user(org, Role.VP_SALES);

        JsonNode rules = ok(getAs(manager, "/api/duplicate-rules"));
        assertThat(rules).hasSize(3);
        assertThat(rules.get(0).path("objectType").asText()).isEqualTo("LEAD");
        assertThat(rules.get(0).path("actionOnCreate").asText()).isEqualTo("WARN");
        assertThat(rules.get(0).path("actionOnEdit").asText()).isEqualTo("WARN");
        assertThat(rules.get(0).path("enabled").asBoolean()).isTrue();

        putAs(manager, "/api/duplicate-rules/lead", m("actionOnCreate", "BLOCK")).andExpect(status().isForbidden());
        putAs(vp, "/api/duplicate-rules/lead", m("actionOnEdit", "ALLOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionOnEdit").value("ALLOW"))
                .andExpect(jsonPath("$.actionOnCreate").value("WARN"));
        putAs(vp, "/api/duplicate-rules/lead", m("actionOnEdit", "MAYBE")).andExpect(status().isBadRequest());
        putAs(vp, "/api/duplicate-rules/opportunity", m("actionOnEdit", "WARN")).andExpect(status().isBadRequest());
        assertThat(ok(getAs(manager, "/api/duplicate-rules")).get(0).path("actionOnEdit").asText()).isEqualTo("ALLOW");
    }
}
