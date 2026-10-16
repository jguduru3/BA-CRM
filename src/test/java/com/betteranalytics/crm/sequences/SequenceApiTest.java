package com.betteranalytics.crm.sequences;

import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRUD, validation and permissions of sequences and enrollments. */
class SequenceApiTest extends SequenceTestSupport {

    @Test
    void managersBuildSequencesRepsOnlySeeThem() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User support = user(org, Role.SUPPORT);

        long id = createSequence(manager, email(0, "Hi {{lead.name}}", "Hello"), whatsapp(2), task(3, "Call {{record.name}}"));
        JsonNode seq = body(getAs(rep, "/api/sequences/" + id).andExpect(status().isOk()));
        assertThat(seq.path("steps")).hasSize(3);
        assertThat(seq.path("steps").get(1).path("channel").asText()).isEqualTo("WHATSAPP");
        assertThat(seq.path("steps").get(1).path("waTemplateParams").get(0).asText()).isEqualTo("{{record.firstName}}");
        assertThat(body(getAs(support, "/api/sequences")).findValuesAsText("name")).contains(seq.path("name").asText());

        postAs(rep, "/api/sequences", Map.of("name", "mine", "steps", List.of())).andExpect(status().isForbidden());
        putAs(rep, "/api/sequences/" + id, Map.of("name", "renamed")).andExpect(status().isForbidden());
        deleteAs(rep, "/api/sequences/" + id).andExpect(status().isForbidden());

        // steps are replaced as a whole
        JsonNode updated = body(putAs(manager, "/api/sequences/" + id,
                Map.of("name", "Renamed", "steps", List.of(task(0, "Only step")))).andExpect(status().isOk()));
        assertThat(updated.path("name").asText()).isEqualTo("Renamed");
        assertThat(updated.path("steps")).hasSize(1);

        // other tenants can't see it
        User other = user(newOrg(), Role.SUPER_ADMIN);
        getAs(other, "/api/sequences/" + id).andExpect(status().isNotFound());
    }

    @Test
    void stepsAreValidated() throws Exception {
        User manager = user(newOrg(), Role.MANAGER);
        postAs(manager, "/api/sequences", Map.of("name", "x", "steps",
                List.of(Map.of("channel", "EMAIL", "body", "no subject")))).andExpect(status().isBadRequest());
        postAs(manager, "/api/sequences", Map.of("name", "x", "steps",
                List.of(Map.of("channel", "SMS", "subject", "s")))).andExpect(status().isBadRequest());
        postAs(manager, "/api/sequences", Map.of("name", "x", "steps",
                List.of(Map.of("channel", "WHATSAPP", "waTemplateName", "bad name!")))).andExpect(status().isBadRequest());
        postAs(manager, "/api/sequences", Map.of("name", "x", "steps",
                List.of(Map.of("channel", "TASK", "subject", "t", "delayDays", 999)))).andExpect(status().isBadRequest());
        postAs(manager, "/api/sequences", Map.of("steps", List.of())).andExpect(status().isBadRequest());
    }

    @Test
    void enrollingNeedsEditOnTheRecordAndIsUniquePerSequence() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User otherRep = user(org, Role.INSIDE_SALES, manager.getId());
        User support = user(org, Role.SUPPORT);
        Lead mine = lead(org, rep.getId(), uniqueEmail(), uniquePhone());
        long seq = createSequence(manager, task(0, "Call"));

        long enr = enroll(rep, seq, "Lead", mine.getId());
        assertThat(enrollment(enr).getStatus()).isEqualTo("ACTIVE");
        assertThat(enrollment(enr).getOwnerUserId()).isEqualTo(rep.getId());

        // one live enrollment per (sequence, record)
        postAs(rep, "/api/sequences/" + seq + "/enrollments", Map.of("relatedType", "Lead", "relatedId", mine.getId()))
                .andExpect(status().isConflict());
        // someone else's lead / read-only role
        postAs(otherRep, "/api/sequences/" + seq + "/enrollments", Map.of("relatedType", "Lead", "relatedId", mine.getId()))
                .andExpect(status().isForbidden());
        postAs(support, "/api/sequences/" + seq + "/enrollments", Map.of("relatedType", "Lead", "relatedId", mine.getId()))
                .andExpect(status().isForbidden());
        postAs(otherRep, "/api/sequence-enrollments/" + enr + "/pause", Map.of()).andExpect(status().isForbidden());

        // pause / resume / stop (unenroll), then re-enrolling is allowed
        assertThat(body(postAs(rep, "/api/sequence-enrollments/" + enr + "/pause", Map.of())
                .andExpect(status().isOk())).path("status").asText()).isEqualTo("PAUSED");
        postAs(rep, "/api/sequence-enrollments/" + enr + "/pause", Map.of()).andExpect(status().isConflict());
        assertThat(body(postAs(rep, "/api/sequence-enrollments/" + enr + "/resume", Map.of()))
                .path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body(postAs(manager, "/api/sequence-enrollments/" + enr + "/stop", Map.of()))
                .path("status").asText()).isEqualTo("STOPPED");
        long again = enroll(rep, seq, "Lead", mine.getId());
        assertThat(again).isNotEqualTo(enr);

        // record view + history
        JsonNode forRecord = body(getAs(rep, "/api/sequence-enrollments?relatedType=Lead&relatedId=" + mine.getId()));
        assertThat(forRecord).hasSize(2);
        JsonNode list = body(getAs(manager, "/api/sequences/" + seq + "/enrollments?status=ACTIVE"));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("recordName").asText()).isEqualTo(mine.getName());
        assertThat(body(getAs(otherRep, "/api/sequences/" + seq + "/enrollments"))).isEmpty();
        assertThat(detail(rep, enr).path("events").findValuesAsText("type"))
                .containsExactly("ENROLLED", "PAUSED", "RESUMED", "STOPPED");
    }

    @Test
    void inactiveOrDeletedSequencesTakeNoEnrollmentsAndDeletingStopsLiveOnes() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        Lead l = lead(org, manager.getId(), uniqueEmail(), null);
        Lead l2 = lead(org, manager.getId(), uniqueEmail(), null);
        long seq = createSequence(manager, task(1, "Call"));
        long enr = enroll(manager, seq, "Lead", l.getId());

        putAs(manager, "/api/sequences/" + seq, Map.of("active", false)).andExpect(status().isOk());
        postAs(manager, "/api/sequences/" + seq + "/enrollments", Map.of("relatedType", "Lead", "relatedId", l2.getId()))
                .andExpect(status().isConflict());

        deleteAs(manager, "/api/sequences/" + seq).andExpect(status().isNoContent());
        assertThat(enrollment(enr).getStatus()).isEqualTo("STOPPED");
        getAs(manager, "/api/sequences/" + seq).andExpect(status().isNotFound());
    }
}
