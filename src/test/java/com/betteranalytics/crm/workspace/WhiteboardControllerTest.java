package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AttachmentRepository;
import com.betteranalytics.crm.repository.WhiteboardItemRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WhiteboardControllerTest extends IntegrationTest {

    @Autowired WhiteboardItemRepository itemRepository;
    @Autowired AttachmentRepository attachmentRepository;

    private long board(User u) throws Exception {
        return idOf(postAs(u, "/api/whiteboards", Map.of("name", "Plan")).andExpect(status().isCreated()));
    }

    private long item(User u, long board, Map<String, Object> body) throws Exception {
        return idOf(postAs(u, "/api/whiteboards/" + board + "/items", body).andExpect(status().isCreated()));
    }

    private long upload(User u, long board) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "pic.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0});
        return idOf(mvc.perform(as(u, multipart("/api/attachments").file(file)
                .param("relatedType", "Whiteboard").param("relatedId", String.valueOf(board))))
                .andExpect(status().isCreated()));
    }

    @Test
    void otherOrganisationGets404AndSupportIsReadOnly() throws Exception {
        Long org = newOrg();
        User owner = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long b = board(owner);
        long i = item(owner, b, Map.of("type", "sticky", "content", "hi"));

        getAs(stranger, "/api/whiteboards/" + b).andExpect(status().isNotFound());
        putAs(stranger, "/api/whiteboards/" + b, Map.of("name", "x")).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/whiteboards/" + b).andExpect(status().isNotFound());
        postAs(stranger, "/api/whiteboards/" + b + "/items", Map.of("type", "sticky")).andExpect(status().isNotFound());
        putAs(stranger, "/api/whiteboards/items/" + i, Map.of("x", 5)).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/whiteboards/items/" + i).andExpect(status().isNotFound());

        JsonNode view = body(getAs(support, "/api/whiteboards/" + b).andExpect(status().isOk()));
        assertThat(view.path("canEdit").asBoolean()).isFalse();
        postAs(support, "/api/whiteboards", Map.of("name", "x")).andExpect(status().isForbidden());
        postAs(support, "/api/whiteboards/" + b + "/items", Map.of("type", "sticky")).andExpect(status().isForbidden());
        putAs(support, "/api/whiteboards/items/" + i, Map.of("x", 5)).andExpect(status().isForbidden());
        postAs(support, "/api/whiteboards/" + b + "/items/bulk", List.of(Map.of("id", i, "x", 1))).andExpect(status().isForbidden());
        deleteAs(support, "/api/whiteboards/items/" + i).andExpect(status().isForbidden());
        deleteAs(support, "/api/whiteboards/" + b).andExpect(status().isForbidden());
    }

    @Test
    void repsCanEditButOnlyDeleteTheirOwnBoardsAndItems() throws Exception {
        Long org = newOrg();
        User owner = user(org, Role.EXECUTIVE);
        User peer = user(org, Role.FIELD_SALES);
        User manager = user(org, Role.MANAGER);
        long b = board(owner);
        long i = item(owner, b, Map.of("type", "sticky"));
        long mine = item(peer, b, Map.of("type", "sticky"));
        putAs(peer, "/api/whiteboards/items/" + i, Map.of("x", 50)).andExpect(status().isOk());
        deleteAs(peer, "/api/whiteboards/items/" + i).andExpect(status().isForbidden());
        deleteAs(peer, "/api/whiteboards/items/" + mine).andExpect(status().isOk());
        deleteAs(peer, "/api/whiteboards/" + b).andExpect(status().isForbidden());
        deleteAs(manager, "/api/whiteboards/" + b).andExpect(status().isOk());
    }

    @Test
    void connectorsJoinTwoElementsAndDieWithThem() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long b = board(u);
        long a = item(u, b, Map.of("type", "sticky", "x", 0, "y", 0));
        long c = item(u, b, Map.of("type", "shape", "x", 400, "y", 0));
        long d = item(u, b, Map.of("type", "text", "x", 0, "y", 300));
        long ac = item(u, b, Map.of("type", "connector", "fromItemId", a, "toItemId", c, "content", "depends on"));
        long dc = item(u, b, Map.of("type", "connector", "fromItemId", d, "toItemId", c, "arrowhead", "both"));

        JsonNode items = body(getAs(u, "/api/whiteboards/" + b)).path("items");
        JsonNode conn = null;
        for (JsonNode n : items) if (n.path("id").asLong() == ac) conn = n;
        assertThat(conn).isNotNull();
        assertThat(conn.path("fromItemId").asLong()).isEqualTo(a);
        assertThat(conn.path("toItemId").asLong()).isEqualTo(c);
        assertThat(conn.path("arrowhead").asText()).isEqualTo("end");
        assertThat(conn.path("content").asText()).isEqualTo("depends on");

        // invalid connectors
        postAs(u, "/api/whiteboards/" + b + "/items", Map.of("type", "connector", "fromItemId", a)).andExpect(status().isBadRequest());
        postAs(u, "/api/whiteboards/" + b + "/items", Map.of("type", "connector", "fromItemId", a, "toItemId", a)).andExpect(status().isBadRequest());
        postAs(u, "/api/whiteboards/" + b + "/items", Map.of("type", "connector", "fromItemId", a, "toItemId", ac)).andExpect(status().isBadRequest());
        long other = board(u);
        long foreign = item(u, other, Map.of("type", "sticky"));
        postAs(u, "/api/whiteboards/" + b + "/items", Map.of("type", "connector", "fromItemId", a, "toItemId", foreign)).andExpect(status().isBadRequest());
        postAs(u, "/api/whiteboards/" + b + "/items", Map.of("type", "connector", "fromItemId", a, "toItemId", c, "arrowhead", "zigzag")).andExpect(status().isBadRequest());

        // moving an element keeps the connector attached (it stores endpoints, not coordinates)
        postAs(u, "/api/whiteboards/" + b + "/items/bulk", List.of(Map.of("id", a, "x", 900, "y", 900))).andExpect(status().isOk());
        assertThat(itemRepository.findById(ac).orElseThrow().getFromItemId()).isEqualTo(a);

        // deleting an element deletes its connectors, and only those
        JsonNode del = body(deleteAs(u, "/api/whiteboards/items/" + c).andExpect(status().isOk()));
        assertThat(del.path("deletedConnectors").asInt()).isEqualTo(2);
        assertThat(itemRepository.findById(ac)).isEmpty();
        assertThat(itemRepository.findById(dc)).isEmpty();
        assertThat(itemRepository.findById(a)).isPresent();
    }

    @Test
    void partialItemUpdateKeepsGeometryAndColour() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long b = board(u);
        long i = item(u, b, Map.of("type", "shape", "x", 10, "y", 20, "w", 300, "h", 90, "color", "#00ff00", "zIndex", 4, "rotation", 15));
        JsonNode r = body(putAs(u, "/api/whiteboards/items/" + i, Map.of("content", "label")).andExpect(status().isOk()));
        assertThat(r.path("x").asDouble()).isEqualTo(10);
        assertThat(r.path("y").asDouble()).isEqualTo(20);
        assertThat(r.path("w").asDouble()).isEqualTo(300);
        assertThat(r.path("color").asText()).isEqualTo("#00ff00");
        assertThat(r.path("zIndex").asInt()).isEqualTo(4);
        assertThat(r.path("rotation").asDouble()).isEqualTo(15);
        JsonNode boardJson = body(putAs(u, "/api/whiteboards/" + b, Map.of("description", "d")).andExpect(status().isOk()));
        assertThat(boardJson.path("name").asText()).isEqualTo("Plan");
    }

    @Test
    void deletingAnAttachmentUsedOnTheBoardClearsTheReference() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long b = board(u);
        long att = upload(u, b);
        long img = item(u, b, Map.of("type", "image", "attachmentId", att));
        deleteAs(u, "/api/attachments/" + att).andExpect(status().isOk());
        assertThat(itemRepository.findById(img).orElseThrow().getAttachmentId()).isNull();
    }

    @Test
    void deletingABoardWithItemsConnectorsAndFilesNever500s() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long b = board(u);
        long att = upload(u, b);
        long img = item(u, b, Map.of("type", "image", "attachmentId", att));
        long s = item(u, b, Map.of("type", "sticky"));
        long conn = item(u, b, Map.of("type", "connector", "fromItemId", img, "toItemId", s));
        assertThat(body(getAs(u, "/api/whiteboards")).get(0).path("itemCount").asLong()).isEqualTo(3);
        deleteAs(u, "/api/whiteboards/" + b).andExpect(status().isOk());
        assertThat(itemRepository.findById(conn)).isEmpty();
        assertThat(itemRepository.findById(img)).isEmpty();
        assertThat(attachmentRepository.findById(att)).isEmpty();
    }
}
