package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.AuditEvent;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Streamed CSV exports: scope, masks, formula injection, BOM, audit, Support, legacy path. */
class ExportTest extends CrmTestSupport {

    private static String csv(MvcResult r) {
        byte[] bytes = r.getResponse().getContentAsByteArray();
        assertThat(bytes.length).isGreaterThanOrEqualTo(3);
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    @Test
    void leadsExportIsScopedFormulaSafeAndAudited() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        User otherRep = user(org, Role.EXECUTIVE);
        lead(rep, m("name", "=HYPERLINK(\"http://evil\")", "email", "ünïcode@example.com", "notes", "x"));
        lead(rep, m("name", "+1 555", "businessName", "Comma, Inc"));
        lead(otherRep, m("name", "Not mine"));

        MvcResult r = stream(rep, "/api/exports/leads");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("leads-");
        assertThat(r.getResponse().getContentType()).startsWith("text/csv");
        String body = csv(r);
        List<String> lines = body.lines().toList();
        assertThat(lines.get(0)).startsWith("id,name,email");
        assertThat(lines).hasSize(3);
        assertThat(body).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"");
        assertThat(body).contains(",'+1 555,");
        assertThat(body).contains("\"Comma, Inc\"");
        assertThat(body).contains("ünïcode@example.com");
        assertThat(body).doesNotContain("Not mine");

        List<AuditEvent> exports = audits(org, "export");
        assertThat(exports).hasSize(1);
        assertThat(exports.get(0).getResource()).isEqualTo("leads");
        assertThat(exports.get(0).getAfterJson()).contains("\"rows\":2");
    }

    @Test
    void exportAppliesFieldMasks() throws Exception {
        Long org = newOrg();
        User inside = user(org, Role.INSIDE_SALES);
        long leadId = lead(inside, m("email", "hidden@client.example", "value", 777_777));
        postAs(inside, "/api/leads/" + leadId + "/convert", Map.of());

        String leads = csv(stream(inside, "/api/exports/leads"));
        assertThat(leads).doesNotContain("hidden@client.example").contains(CrmMasks.MASK);

        String opps = csv(stream(inside, "/api/exports/opportunities"));
        assertThat(opps.lines().count()).isEqualTo(2);
        assertThat(opps).doesNotContain("777777");
    }

    @Test
    void supportCanExportWhatItCanReadAndLegacyPathStillWorks() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User support = user(org, Role.SUPPORT);
        long companyId = company(admin, m("name", "Exported Co", "website", "exported.example"));
        contact(admin, m("name", "Exported Person", "companyId", companyId));
        activity(admin, m("relatedType", "Company", "relatedId", companyId, "subject", "Kickoff"));

        assertThat(csv(stream(support, "/api/exports/companies"))).contains("Exported Co").contains("https://exported.example");
        assertThat(csv(stream(support, "/api/exports/contacts"))).contains("Exported Person");
        // Support may export the timeline, but other people's activity content stays masked
        assertThat(csv(stream(support, "/api/exports/activities"))).contains("[restricted]").doesNotContain("Kickoff");
        assertThat(csv(stream(admin, "/api/exports/activities"))).contains("Kickoff");
        // Support reads leads and deals org-wide (read-only role), so it may export them too
        assertThat(stream(support, "/api/exports/leads").getResponse().getStatus()).isEqualTo(200);
        assertThat(stream(support, "/api/exports/opportunities").getResponse().getStatus()).isEqualTo(200);
        assertThat(stream(admin, "/api/exports/nonsense").getResponse().getStatus()).isEqualTo(404);

        // old URL kept as an alias
        assertThat(csv(stream(admin, "/api/export/companies.csv"))).contains("Exported Co");
    }

    @Test
    void exportStreamsAcrossManyChunks() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        int n = 500 * 2 + 7; // more than two keyset chunks
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(new Object[]{org, "Bulk " + i, admin.getId()});
        jdbcTemplate.batchUpdate("INSERT INTO companies (organization_id, name, status, owner_id, created_at, updated_at) " +
                                 "VALUES (?, ?, 'Prospect', ?, now(), now())", rows);
        String body = csv(stream(admin, "/api/exports/companies"));
        assertThat(body.lines().count()).isEqualTo(n + 1L);
        assertThat(body).contains("Bulk 0,").contains("Bulk " + (n - 1) + ",");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
}
