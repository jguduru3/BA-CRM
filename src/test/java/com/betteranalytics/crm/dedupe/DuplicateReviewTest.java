package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/duplicates — candidate groups, scoped to what the caller may view, paginated. */
class DuplicateReviewTest extends DedupeTestSupport {

    private static List<Long> ids(JsonNode group) {
        List<Long> out = new ArrayList<>();
        group.path("records").forEach(r -> out.add(r.path("id").asLong()));
        return out;
    }

    @Test
    void groupsLeadsByExactKeysAndFuzzyNamesWithinBlocks() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User other = user(org, Role.EXECUTIVE, manager.getId());

        long e1 = create(rep, "/api/leads", m("name", "Ann Perkins", "email", "ann@pawnee.gov"));
        long e2 = create(rep, "/api/leads", m("name", "Ann P.", "email", "ANN@pawnee.gov "));
        long f1 = create(other, "/api/leads", m("name", "Jonathan Smith", "businessName", "Smith & Co"));
        long f2 = create(other, "/api/leads", m("name", "Jonathon Smith", "businessName", "Smith and Co"));
        create(rep, "/api/leads", m("name", "Leslie Knope", "email", "leslie@pawnee.gov"));
        long p1 = create(other, "/api/leads", m("name", "Ron Swanson", "phone", "+1 (555) 010-0199"));
        long p2 = create(rep, "/api/leads", m("name", "R Swanson", "phone", "+15550100199"));

        JsonNode all = body(getAs(manager, "/api/duplicates?type=lead").andExpect(status().isOk()));
        assertThat(all.path("count").asInt()).isEqualTo(3);
        List<List<Long>> groups = new ArrayList<>();
        all.path("data").forEach(g -> groups.add(ids(g)));
        assertThat(groups).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(e1, e2));
        assertThat(groups).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(f1, f2));
        assertThat(groups).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(p1, p2));
        JsonNode best = all.path("data").get(0);
        assertThat(best.path("score").asDouble()).isEqualTo(1.0);
        assertThat(best.path("matchedOn").toString()).contains("email");
        assertThat(best.path("records").get(0).has("emailNormalized")).isFalse();

        // a rep only sees groups among their own leads — the phone pair spans two owners
        JsonNode mine = body(getAs(rep, "/api/duplicates?type=lead").andExpect(status().isOk()));
        assertThat(mine.path("count").asInt()).isEqualTo(1);
        assertThat(ids(mine.path("data").get(0))).containsExactlyInAnyOrder(e1, e2);

        JsonNode page = body(getAs(manager, "/api/duplicates?type=lead&page=1&size=2").andExpect(status().isOk()));
        assertThat(page.path("count").asInt()).isEqualTo(3);
        assertThat(page.path("data")).hasSize(1);
    }

    @Test
    void groupsCompaniesByDomainAndContactsByEmail() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        long a = create(vp, "/api/companies", m("name", "Vandelay Industries", "website", "https://vandelay.com"));
        long b = create(vp, "/api/companies", m("name", "Vandelay Import Export", "website", "www.vandelay.com/contact"));
        create(vp, "/api/companies", m("name", "Kramerica", "website", "kramerica.com"));

        JsonNode companies = body(getAs(vp, "/api/duplicates?type=companies").andExpect(status().isOk()));
        assertThat(companies.path("count").asInt()).isEqualTo(1);
        assertThat(ids(companies.path("data").get(0))).containsExactlyInAnyOrder(a, b);
        assertThat(companies.path("data").get(0).path("matchedOn").toString()).contains("domain");
        // oldest first: the natural primary
        assertThat(ids(companies.path("data").get(0)).get(0)).isEqualTo(a);

        long c1 = create(vp, "/api/contacts", m("name", "Art Vandelay", "email", "art@vandelay.com"));
        long c2 = create(vp, "/api/contacts", m("name", "Arthur Vandelay", "email", "art@vandelay.com"));
        JsonNode contacts = body(getAs(vp, "/api/duplicates?type=contact").andExpect(status().isOk()));
        assertThat(contacts.path("count").asInt()).isEqualTo(1);
        assertThat(ids(contacts.path("data").get(0))).containsExactlyInAnyOrder(c1, c2);
    }

    @Test
    void rejectsUnknownTypesAndRolesWithoutAccess() throws Exception {
        User vp = user(newOrg(), Role.VP_SALES);
        getAs(vp, "/api/duplicates?type=opportunity").andExpect(status().isBadRequest());
        JsonNode empty = body(getAs(vp, "/api/duplicates?type=lead").andExpect(status().isOk()));
        assertThat(empty.path("count").asInt()).isZero();
        assertThat(empty.path("data")).isEmpty();
    }
}
