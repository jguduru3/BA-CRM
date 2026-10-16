package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AttachmentControllerTest extends IntegrationTest {

    private ResultActions upload(User u, String type, long id) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());
        return mvc.perform(as(u, multipart("/api/attachments").file(file)
                .param("relatedType", type).param("relatedId", String.valueOf(id))));
    }

    @Test
    void attachmentsFollowTheirParentsPermissions() throws Exception {
        Long org = newOrg();
        User owner = user(org, Role.EXECUTIVE);
        User peer = user(org, Role.FIELD_SALES);
        User support = user(org, Role.SUPPORT);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long project = idOf(postAs(owner, "/api/projects", Map.of("name", "P")).andExpect(status().isCreated()));
        long att = idOf(upload(owner, "Project", project).andExpect(status().isCreated()));

        // another org: the parent doesn't exist for them
        upload(stranger, "Project", project).andExpect(status().isNotFound());
        getAs(stranger, "/api/attachments?relatedType=Project&relatedId=" + project).andExpect(status().isNotFound());
        getAs(stranger, "/api/attachments/" + att + "/download").andExpect(status().isNotFound());
        deleteAs(stranger, "/api/attachments/" + att).andExpect(status().isNotFound());

        // SUPPORT reads but never writes
        getAs(support, "/api/attachments?relatedType=Project&relatedId=" + project).andExpect(status().isOk());
        upload(support, "Project", project).andExpect(status().isForbidden());
        deleteAs(support, "/api/attachments/" + att).andExpect(status().isForbidden());

        // a rep who is not a project member cannot add files; a member can
        upload(peer, "Project", project).andExpect(status().isForbidden());
        postAs(owner, "/api/projects/" + project + "/members", Map.of("userId", peer.getId(), "role", "member")).andExpect(status().isCreated());
        upload(peer, "Project", project).andExpect(status().isCreated());
        assertThat(body(getAs(owner, "/api/attachments?relatedType=Project&relatedId=" + project)).size()).isEqualTo(2);
    }
}
