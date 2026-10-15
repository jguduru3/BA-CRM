package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Company;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.CompanyRepository;
import com.betteranalytics.crm.repository.PageBlockRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecordPageControllerTest extends IntegrationTest {

    @Autowired CompanyRepository companyRepository;
    @Autowired PageBlockRepository blockRepository;

    private long company(User owner) {
        Company c = new Company();
        c.setOrganizationId(owner.getOrganizationId());
        c.setName("Acme " + System.nanoTime());
        c.setOwnerId(owner.getId());
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return companyRepository.save(c).getId();
    }

    private String base(long companyId) {
        return "/api/pages/Company/" + companyId;
    }

    private long block(User u, long companyId, Map<String, Object> body) throws Exception {
        return idOf(postAs(u, base(companyId) + "/blocks", body).andExpect(status().isCreated()));
    }

    @Test
    void otherOrganisationGets404AndSupportIsReadOnly() throws Exception {
        Long org = newOrg();
        User owner = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long c = company(owner);
        long b = block(owner, c, Map.of("blockType", "paragraph", "content", Map.of("text", "hello")));

        getAs(stranger, base(c)).andExpect(status().isNotFound());
        postAs(stranger, base(c) + "/blocks", Map.of("blockType", "paragraph")).andExpect(status().isNotFound());
        putAs(stranger, base(c) + "/blocks/" + b, Map.of("content", Map.of("text", "x"))).andExpect(status().isNotFound());

        JsonNode page = body(getAs(support, base(c)).andExpect(status().isOk()));
        assertThat(page.path("canEdit").asBoolean()).isFalse();
        assertThat(page.path("blocks").size()).isEqualTo(1);
        postAs(support, base(c) + "/blocks", Map.of("blockType", "paragraph")).andExpect(status().isForbidden());
        putAs(support, base(c) + "/blocks/" + b, Map.of("content", Map.of("text", "x"))).andExpect(status().isForbidden());
        deleteAs(support, base(c) + "/blocks/" + b).andExpect(status().isForbidden());
        putAs(support, base(c) + "/blocks/order", Map.of("blockIds", List.of(b))).andExpect(status().isForbidden());
    }

    @Test
    void blockEditorSupportsHeadingsParagraphsDividersAndTodos() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long c = company(u);
        long h1 = block(u, c, Map.of("blockType", "heading_1", "content", Map.of("text", "Title")));
        long p = block(u, c, Map.of("blockType", "paragraph", "content", "plain <b>text</b>"));
        long d = block(u, c, Map.of("blockType", "divider"));
        long t = block(u, c, Map.of("blockType", "todo", "content", Map.of("text", "do it"), "checked", true));
        // insert after the heading
        long h2 = block(u, c, Map.of("blockType", "heading_2", "content", Map.of("text", "Sub"), "afterBlockId", h1));

        JsonNode blocks = body(getAs(u, base(c))).path("blocks");
        List<Long> order = new ArrayList<>();
        blocks.forEach(n -> order.add(n.path("id").asLong()));
        assertThat(order).containsExactly(h1, h2, p, d, t);
        assertThat(json.readTree(blocks.get(2).path("content").asText()).path("text").asText()).isEqualTo("plain <b>text</b>");
        assertThat(blocks.get(4).path("checked").asBoolean()).isTrue();

        postAs(u, base(c) + "/blocks", Map.of("blockType", "script")).andExpect(status().isBadRequest());
        postAs(u, base(c) + "/blocks", Map.of("blockType", "paragraph", "content", Map.of("text", 5))).andExpect(status().isBadRequest());
    }

    @Test
    void reorderRewritesAllPositionsInOneCall() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long c = company(u);
        long a = block(u, c, Map.of("content", Map.of("text", "a")));
        long b = block(u, c, Map.of("content", Map.of("text", "b")));
        long d = block(u, c, Map.of("content", Map.of("text", "c")));
        JsonNode result = body(putAs(u, base(c) + "/blocks/order", Map.of("blockIds", List.of(d, a, b))).andExpect(status().isOk()));
        assertThat(result.get(0).path("id").asLong()).isEqualTo(d);
        assertThat(result.get(0).path("position").asInt()).isZero();
        assertThat(result.get(2).path("id").asLong()).isEqualTo(b);
        // must list every block exactly once
        putAs(u, base(c) + "/blocks/order", Map.of("blockIds", List.of(a, b))).andExpect(status().isBadRequest());
        putAs(u, base(c) + "/blocks/order", Map.of("blockIds", List.of(a, a, b))).andExpect(status().isBadRequest());
    }

    @Test
    void partialBlockUpdateKeepsTypePositionAndCheckedState() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long c = company(u);
        block(u, c, Map.of("content", Map.of("text", "first")));
        long t = block(u, c, Map.of("blockType", "todo", "content", Map.of("text", "x"), "checked", true));
        JsonNode updated = body(putAs(u, base(c) + "/blocks/" + t, Map.of("content", Map.of("text", "y"))).andExpect(status().isOk()));
        assertThat(updated.path("blockType").asText()).isEqualTo("todo");
        assertThat(updated.path("checked").asBoolean()).isTrue();
        assertThat(updated.path("position").asInt()).isEqualTo(1);
        // changing only the type keeps the text
        JsonNode retyped = body(putAs(u, base(c) + "/blocks/" + t, Map.of("blockType", "heading_3")).andExpect(status().isOk()));
        assertThat(json.readTree(retyped.path("content").asText()).path("text").asText()).isEqualTo("y");
    }

    @Test
    void deletingAPageWithBlocksNever500s() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long c = company(u);
        long a = block(u, c, Map.of("content", Map.of("text", "a")));
        block(u, c, Map.of("blockType", "divider"));
        JsonNode r = body(deleteAs(u, base(c)).andExpect(status().isOk()));
        assertThat(r.path("deletedBlocks").asInt()).isEqualTo(2);
        assertThat(blockRepository.findById(a)).isEmpty();
        assertThat(body(getAs(u, base(c))).path("blocks").size()).isZero();
    }

    @Test
    void documentsAreBlockPagesAndSurviveSoftDeleteAndRestore() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long doc = idOf(postAs(admin, "/api/documents", Map.of("title", "Spec")).andExpect(status().isOk()));
        idOf(postAs(admin, "/api/pages/Document/" + doc + "/blocks", Map.of("blockType", "heading_1", "content", Map.of("text", "Spec")))
                .andExpect(status().isCreated()));
        deleteAs(admin, "/api/documents/" + doc).andExpect(status().isNoContent());
        getAs(admin, "/api/pages/Document/" + doc).andExpect(status().isNotFound());
        postAs(admin, "/api/documents/" + doc + "/restore", Map.of()).andExpect(status().isOk());
        assertThat(body(getAs(admin, "/api/pages/Document/" + doc)).path("blocks").size()).isEqualTo(1);
    }
}
