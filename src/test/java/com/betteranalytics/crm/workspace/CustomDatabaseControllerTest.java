package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.DatabaseRecordRepository;
import com.betteranalytics.crm.repository.PropertyValueRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomDatabaseControllerTest extends IntegrationTest {

    @Autowired DatabaseRecordRepository recordRepository;
    @Autowired PropertyValueRepository valueRepository;

    private long db(User u, String name) throws Exception {
        return idOf(postAs(u, "/api/databases", Map.of("name", name)).andExpect(status().isOk()));
    }

    private long prop(User u, long db, String name, String type, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "type", type));
        body.putAll(extra);
        return idOf(postAs(u, "/api/databases/" + db + "/properties", body).andExpect(status().isOk()));
    }

    private JsonNode record(User u, long db, List<Map<String, Object>> values) throws Exception {
        return body(postAs(u, "/api/databases/" + db + "/records", Map.of("values", values)).andExpect(status().isOk()));
    }

    private static Map<String, Object> v(long propertyId, String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put("propertyId", propertyId);
        m.put(key, value);
        return m;
    }

    @Test
    void tenantIsolationAndRoles() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long d = db(rep, "Deals");
        long name = prop(rep, d, "Name", "text", Map.of());
        long rec = record(rep, d, List.of(v(name, "textValue", "A"))).path("record").path("id").asLong();

        getAs(stranger, "/api/databases/" + d).andExpect(status().isNotFound());
        getAs(stranger, "/api/databases/" + d + "/records").andExpect(status().isNotFound());
        putAs(stranger, "/api/databases/" + d + "/records/" + rec, Map.of("values", List.of())).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/databases/" + d).andExpect(status().isNotFound());

        getAs(support, "/api/databases/" + d + "/records").andExpect(status().isOk());
        assertThat(body(getAs(support, "/api/databases/" + d)).path("canEdit").asBoolean()).isFalse();
        postAs(support, "/api/databases", Map.of("name", "x")).andExpect(status().isForbidden());
        postAs(support, "/api/databases/" + d + "/records", Map.of("values", List.of())).andExpect(status().isForbidden());
        putAs(support, "/api/databases/" + d + "/records/" + rec, Map.of("values", List.of())).andExpect(status().isForbidden());
        deleteAs(support, "/api/databases/" + d + "/records/" + rec).andExpect(status().isForbidden());
        postAs(support, "/api/databases/" + d + "/properties", Map.of("name", "x", "type", "text")).andExpect(status().isForbidden());
        // schema deletion is VP / Super Admin only
        deleteAs(rep, "/api/databases/" + d).andExpect(status().isForbidden());
    }

    @Test
    void typedValuesAreValidated() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long d = db(u, "Contacts");
        long url = prop(u, d, "Website", "url", Map.of());
        long email = prop(u, d, "Email", "email", Map.of());
        long phone = prop(u, d, "Phone", "phone", Map.of());
        long num = prop(u, d, "Score", "number", Map.of());
        long date = prop(u, d, "Since", "date", Map.of());
        long sel = prop(u, d, "Tier", "select", Map.of("optionsJson", "[{\"id\":\"gold\",\"label\":\"Gold\",\"color\":\"#fc0\"}]"));
        long multi = prop(u, d, "Tags", "multi_select", Map.of("optionsJson", "[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"b\",\"label\":\"B\"}]"));
        long check = prop(u, d, "Active", "checkbox", Map.of());

        String base = "/api/databases/" + d + "/records";
        postAs(u, base, Map.of("values", List.of(v(url, "textValue", "javascript:alert(1)")))).andExpect(status().isBadRequest());
        postAs(u, base, Map.of("values", List.of(v(url, "textValue", "data:text/html,x")))).andExpect(status().isBadRequest());
        postAs(u, base, Map.of("values", List.of(v(email, "textValue", "not-an-email")))).andExpect(status().isBadRequest());
        postAs(u, base, Map.of("values", List.of(v(phone, "textValue", "call me")))).andExpect(status().isBadRequest());
        postAs(u, base, Map.of("values", List.of(v(num, "numberValue", "abc")))).andExpect(status().isBadRequest());
        postAs(u, base, Map.of("values", List.of(v(date, "dateValue", "yesterday")))).andExpect(status().isBadRequest());
        postAs(u, base, Map.of("values", List.of(v(sel, "textValue", "platinum")))).andExpect(status().isBadRequest());
        postAs(u, base, Map.of("values", List.of(v(multi, "jsonValue", List.of("a", "z"))))).andExpect(status().isBadRequest());

        JsonNode ok = record(u, d, List.of(
                v(url, "textValue", "acme.com"), v(email, "textValue", "ada@acme.com"), v(phone, "textValue", "+91 98765-43210"),
                v(num, "numberValue", 42), v(date, "textValue", "2030-01-15"), v(sel, "textValue", "gold"),
                v(multi, "jsonValue", "[\"a\",\"b\"]"), v(check, "boolValue", true)));
        Map<Long, JsonNode> byProp = new HashMap<>();
        ok.path("values").forEach(n -> byProp.put(n.path("propertyId").asLong(), n));
        assertThat(byProp.get(url).path("textValue").asText()).isEqualTo("https://acme.com");
        assertThat(byProp.get(date).path("dateValue").asText()).startsWith("2030-01-15");
        assertThat(byProp.get(num).path("numberValue").asInt()).isEqualTo(42);
        assertThat(byProp.get(check).path("boolValue").asBoolean()).isTrue();
    }

    @Test
    void relationsLinkRecordsOfTheRelatedDatabaseOnly() throws Exception {
        Long org = newOrg();
        User u = user(org, Role.EXECUTIVE);
        long companies = db(u, "Companies");
        long cName = prop(u, companies, "Name", "text", Map.of());
        long acme = record(u, companies, List.of(v(cName, "textValue", "Acme"))).path("record").path("id").asLong();
        long globex = record(u, companies, List.of(v(cName, "textValue", "Globex"))).path("record").path("id").asLong();

        long deals = db(u, "Deals");
        long rel = prop(u, deals, "Company", "relation", Map.of("relationDbId", companies));
        long dealName = prop(u, deals, "Name", "text", Map.of());
        long foreignDealRecord = record(u, deals, List.of(v(dealName, "textValue", "self"))).path("record").path("id").asLong();

        JsonNode r = record(u, deals, List.of(v(rel, "jsonValue", List.of(acme, globex))));
        assertThat(r.path("relations").path(String.valueOf(acme)).asText()).isEqualTo("Acme");
        assertThat(r.path("relations").path(String.valueOf(globex)).asText()).isEqualTo("Globex");

        // a record of another database, a missing record, and another org's record are all rejected
        postAs(u, "/api/databases/" + deals + "/records", Map.of("values", List.of(v(rel, "jsonValue", List.of(foreignDealRecord)))))
                .andExpect(status().isBadRequest());
        postAs(u, "/api/databases/" + deals + "/records", Map.of("values", List.of(v(rel, "jsonValue", List.of(99999999)))))
                .andExpect(status().isBadRequest());
        User other = user(newOrg(), Role.EXECUTIVE);
        long otherDb = db(other, "Theirs");
        long otherName = prop(other, otherDb, "Name", "text", Map.of());
        long theirs = record(other, otherDb, List.of(v(otherName, "textValue", "secret"))).path("record").path("id").asLong();
        postAs(u, "/api/databases/" + deals + "/records", Map.of("values", List.of(v(rel, "jsonValue", List.of(theirs)))))
                .andExpect(status().isBadRequest());
        // relation properties need a related database of the same org
        postAs(u, "/api/databases/" + deals + "/properties", Map.of("name", "X", "type", "relation")).andExpect(status().isBadRequest());
        postAs(u, "/api/databases/" + deals + "/properties", Map.of("name", "X", "type", "relation", "relationDbId", otherDb))
                .andExpect(status().isBadRequest());

        JsonNode list = body(getAs(u, "/api/databases/" + deals + "/records"));
        assertThat(list.size()).isEqualTo(2);
    }

    @Test
    void formulasAreComputedServerSideAndReadOnly() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long d = db(u, "Orders");
        long price = prop(u, d, "Price", "number", Map.of());
        long qty = prop(u, d, "Qty", "number", Map.of());
        long item = prop(u, d, "Item", "text", Map.of());
        long total = prop(u, d, "Total", "formula", Map.of("optionsJson", "{\"expression\":\"{Price} * {Qty}\"}"));
        long label = prop(u, d, "Label", "formula", Map.of("optionsJson", "{\"expression\":\"IF({Total} > 100, {Item} & \\\" (big)\\\", {Item})\"}"));
        long ratio = prop(u, d, "Ratio", "formula", Map.of("optionsJson", "prop(\"Price\") / {Qty}"));

        JsonNode r = record(u, d, List.of(v(price, "numberValue", 30), v(qty, "numberValue", 4), v(item, "textValue", "Desk")));
        assertThat(r.path("formulas").path(String.valueOf(total)).path("value").asInt()).isEqualTo(120);
        assertThat(r.path("formulas").path(String.valueOf(label)).path("value").asText()).isEqualTo("Desk (big)");
        assertThat(r.path("formulas").path(String.valueOf(ratio)).path("value").asDouble()).isEqualTo(7.5);

        long recId = r.path("record").path("id").asLong();
        JsonNode zero = body(putAs(u, "/api/databases/" + d + "/records/" + recId, Map.of("values", List.of(v(qty, "numberValue", 0)))));
        assertThat(zero.path("formulas").path(String.valueOf(ratio)).path("error").asText()).isEqualTo("#DIV/0!");
        assertThat(zero.path("formulas").path(String.valueOf(total)).path("value").asInt()).isZero();

        // formulas cannot be written, and bad expressions are refused up front
        putAs(u, "/api/databases/" + d + "/records/" + recId, Map.of("values", List.of(v(total, "numberValue", 5)))).andExpect(status().isBadRequest());
        postAs(u, "/api/databases/" + d + "/properties", Map.of("name", "Bad", "type", "formula", "optionsJson", "{Price} *")).andExpect(status().isBadRequest());
        postAs(u, "/api/databases/" + d + "/properties", Map.of("name", "Evil", "type", "formula",
                "optionsJson", "java.lang.Runtime.getRuntime().exec(\"calc\")")).andExpect(status().isBadRequest());

        // circular references resolve to an error value, not a crash
        long a = prop(u, d, "A", "formula", Map.of("optionsJson", "{B} + 1"));
        long b = prop(u, d, "B", "formula", Map.of("optionsJson", "{A} + 1"));
        JsonNode cyc = body(getAs(u, "/api/databases/" + d + "/records/" + recId));
        assertThat(cyc.path("formulas").path(String.valueOf(a)).path("error").asText()).isEqualTo("#CYCLE!");
        assertThat(cyc.path("formulas").path(String.valueOf(b)).path("error").asText()).isEqualTo("#CYCLE!");
    }

    @Test
    void partialUpdatesKeepUntouchedValuesAndFlags() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long d = db(u, "Tasks");
        long name = prop(u, d, "Name", "text", Map.of("required", true, "position", 3));
        long done = prop(u, d, "Done", "checkbox", Map.of());
        postAs(u, "/api/databases/" + d + "/records", Map.of("values", List.of(v(done, "boolValue", true)))).andExpect(status().isBadRequest());
        long rec = record(u, d, List.of(v(name, "textValue", "Write spec"), v(done, "boolValue", true))).path("record").path("id").asLong();
        JsonNode after = body(putAs(u, "/api/databases/" + d + "/records/" + rec, Map.of("values", List.of(v(name, "textValue", "Write spec v2")))));
        Map<Long, JsonNode> byProp = new HashMap<>();
        after.path("values").forEach(n -> byProp.put(n.path("propertyId").asLong(), n));
        assertThat(byProp.get(done).path("boolValue").asBoolean()).isTrue();

        JsonNode p = body(putAs(u, "/api/databases/" + d + "/properties/" + name, Map.of("name", "Title")).andExpect(status().isOk()));
        assertThat(p.path("required").asBoolean()).isTrue();
        assertThat(p.path("position").asInt()).isEqualTo(3);

        long view = idOf(postAs(u, "/api/databases/" + d + "/views", Map.of("name", "Board", "type", "kanban", "default", true, "configJson", "{}")));
        JsonNode vw = body(putAs(u, "/api/databases/" + d + "/views/" + view, Map.of("name", "Board 2")).andExpect(status().isOk()));
        assertThat(vw.path("default").asBoolean()).isTrue();
        assertThat(vw.path("configJson").asText()).isEqualTo("{}");
    }

    @Test
    void deletingADatabaseWithRecordsCascades() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long d = db(admin, "Temp");
        long name = prop(admin, d, "Name", "text", Map.of());
        long rec = record(admin, d, List.of(v(name, "textValue", "x"))).path("record").path("id").asLong();
        long other = db(admin, "Links");
        prop(admin, other, "To temp", "relation", Map.of("relationDbId", d));
        deleteAs(admin, "/api/databases/" + d).andExpect(status().isNoContent());
        assertThat(recordRepository.findById(rec)).isEmpty();
        assertThat(valueRepository.findByRecordIdAndOrganizationId(rec, admin.getOrganizationId())).isEmpty();
        getAs(admin, "/api/databases/" + other + "/records").andExpect(status().isOk());
    }
}
