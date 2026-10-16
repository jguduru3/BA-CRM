package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/{leads|contacts|companies}/{id}/merge — field rules, every re-pointed relation, permissions. */
class MergeTest extends DedupeTestSupport {

    @Autowired LeadRepository leadRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired OpportunityRepository opportunityRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired NoteRepository noteRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired MeetingRepository meetingRepository;
    @Autowired CalendarEventRepository calendarEventRepository;
    @Autowired ReminderRepository reminderRepository;
    @Autowired TodoItemRepository todoItemRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired WhatsAppMessageRepository whatsAppMessageRepository;
    @Autowired CampaignRepository campaignRepository;
    @Autowired CampaignContactRepository campaignContactRepository;
    @Autowired AppNotificationRepository notificationRepository;
    @Autowired AiJobRepository aiJobRepository;
    @Autowired RecordPageRepository recordPageRepository;
    @Autowired PageBlockRepository pageBlockRepository;

    private static Object prop(Object bean, String name) {
        return new BeanWrapperImpl(bean).getPropertyValue(name);
    }

    /** Saves a child entity linked to (relatedType, relatedId), filling the given required fields. */
    private <T> T child(JpaRepository<T, Long> repo, Supplier<T> ctor, Long org, String typeProp, String idProp,
                        String relatedType, Long relatedId, Object... kv) {
        T e = ctor.get();
        BeanWrapper w = new BeanWrapperImpl(e);
        w.setPropertyValue("organizationId", org);
        w.setPropertyValue(typeProp, relatedType);
        w.setPropertyValue(idProp, relatedId);
        if (w.isWritableProperty("createdAt")) w.setPropertyValue("createdAt", Instant.now());
        if (w.isWritableProperty("updatedAt")) w.setPropertyValue("updatedAt", Instant.now());
        for (int i = 0; i < kv.length; i += 2) w.setPropertyValue((String) kv[i], kv[i + 1]);
        return repo.save(e);
    }

    /** One child of every related kind, all pointing at (type, id); returns repo → saved entity. */
    private Map<JpaRepository<?, Long>, Object> everyChild(Long org, User u, String type, Long id) {
        Campaign campaign = new Campaign();
        campaign.setOrganizationId(org);
        campaign.setName("Spring promo " + System.nanoTime());
        campaign.setCreatedBy(u.getId());
        campaign.setCreatedAt(Instant.now());
        campaign.setUpdatedAt(Instant.now());
        campaignRepository.save(campaign);

        Map<JpaRepository<?, Long>, Object> out = new LinkedHashMap<>();
        out.put(activityRepository, child(activityRepository, Activity::new, org, "relatedType", "relatedId", type, id,
                "type", "call", "userId", u.getId()));
        out.put(taskRepository, child(taskRepository, Task::new, org, "relatedType", "relatedId", type, id,
                "title", "Follow up", "assigneeId", u.getId()));
        out.put(noteRepository, child(noteRepository, Note::new, org, "relatedType", "relatedId", type, id,
                "body", "met at expo", "createdBy", u.getId()));
        out.put(attachmentRepository, child(attachmentRepository, Attachment::new, org, "relatedType", "relatedId", type, id,
                "fileName", "brief.pdf", "sizeBytes", 10L, "storageKey", "k/" + System.nanoTime()));
        out.put(meetingRepository, child(meetingRepository, Meeting::new, org, "relatedType", "relatedId", type, id,
                "title", "Demo", "startAt", Instant.now(), "createdBy", u.getId()));
        out.put(calendarEventRepository, child(calendarEventRepository, CalendarEvent::new, org, "relatedType", "relatedId",
                type, id, "title", "Call", "startAt", Instant.now(), "ownerId", u.getId(), "createdBy", u.getId()));
        out.put(reminderRepository, child(reminderRepository, Reminder::new, org, "relatedType", "relatedId", type, id,
                "userId", u.getId(), "title", "Ping", "remindAt", Instant.now().plus(1, ChronoUnit.DAYS)));
        out.put(todoItemRepository, child(todoItemRepository, TodoItem::new, org, "relatedType", "relatedId", type, id,
                "title", "Send deck", "createdBy", u.getId()));
        out.put(documentRepository, child(documentRepository, Document::new, org, "relatedType", "relatedId", type, id,
                "title", "Proposal", "createdBy", u.getId()));
        out.put(whatsAppMessageRepository, child(whatsAppMessageRepository, WhatsAppMessage::new, org, "relatedType",
                "relatedId", type, id, "direction", "outbound", "type", "text", "body", "hi"));
        out.put(campaignContactRepository, child(campaignContactRepository, CampaignContact::new, org, "relatedType",
                "relatedId", type, id, "campaignId", campaign.getId(), "phoneNumber", "+919800000001",
                "normalizedPhone", "+919800000001"));
        out.put(notificationRepository, child(notificationRepository, AppNotification::new, org, "relatedType", "relatedId",
                type, id, "userId", u.getId(), "type", "lead_assigned", "title", "Assigned"));
        out.put(aiJobRepository, child(aiJobRepository, AiJob::new, org, "entityType", "entityId", type, id,
                "type", "summarize"));
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertAllPointAt(Map<JpaRepository<?, Long>, Object> children, Long expected) {
        children.forEach((repo, saved) -> {
            Object reloaded = ((JpaRepository) repo).findById(prop(saved, "id")).orElseThrow();
            String idProp = reloaded instanceof AiJob ? "entityId" : "relatedId";
            assertThat(prop(reloaded, idProp))
                    .as(reloaded.getClass().getSimpleName() + " re-pointed")
                    .isEqualTo(expected);
        });
    }

    private PageBlock block(Long pageId, int position, String content) {
        PageBlock b = new PageBlock();
        b.setPageId(pageId);
        b.setPosition(position);
        b.setContent(content);
        b.setCreatedAt(Instant.now());
        b.setUpdatedAt(Instant.now());
        return pageBlockRepository.save(b);
    }

    private RecordPage page(Long org, String type, Long id) {
        RecordPage p = new RecordPage();
        p.setOrganizationId(org);
        p.setRelatedType(type);
        p.setRelatedId(id);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return recordPageRepository.save(p);
    }

    // ── leads ───────────────────────────────────────────────────────────────────

    @Test
    void leadMergePrimaryWinsFillsGapsKeepsOldestAndTrashesSecondaries() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        long primary = create(manager, "/api/leads", m("name", "Gina Linetti", "industry", "Retail", "value", 0));
        long s1 = create(manager, "/api/leads", m("name", "Gina L", "phone", "+919811111111", "industry", "Banking",
                "value", 5000, "geography", "Mumbai"));
        long s2 = create(manager, "/api/leads", m("name", "G Linetti", "email", "gina@nine-nine.com"));
        Instant oldest = Instant.parse("2024-01-02T03:04:05Z");
        jdbc.update("UPDATE leads SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(oldest), s2);

        JsonNode res = body(postAs(manager, "/api/leads/" + primary + "/merge", m("secondaryIds", List.of(s1, s2)))
                .andExpect(status().isOk()));
        assertThat(res.path("mergedIds").toString()).isEqualTo("[" + s1 + "," + s2 + "]");
        JsonNode rec = res.path("record");
        assertThat(rec.path("name").asText()).isEqualTo("Gina Linetti");
        assertThat(rec.path("industry").asText()).isEqualTo("Retail");     // primary wins
        assertThat(rec.path("phone").asText()).isEqualTo("+919811111111"); // gap filled from s1
        assertThat(rec.path("email").asText()).isEqualTo("gina@nine-nine.com"); // gap filled from s2
        assertThat(rec.path("value").asLong()).isEqualTo(5000);
        assertThat(rec.path("geography").asText()).isEqualTo("Mumbai");
        assertThat(rec.path("mergedIds").asText()).isEqualTo(s1 + "," + s2);

        Lead merged = leadRepository.findById(primary).orElseThrow();
        assertThat(merged.getCreatedAt()).isEqualTo(oldest);
        assertThat(merged.getEmailNormalized()).isEqualTo("gina@nine-nine.com");
        assertThat(leadRepository.findById(s1)).isEmpty();
        Lead trashed = leadRepository.findByIdAndOrganizationIdIncludingDeleted(s1, org).orElseThrow();
        assertThat(trashed.getDeleteReason()).isEqualTo("merged into #" + primary);
        assertThat(trashed.getDeletedBy()).isEqualTo(manager.getId());

        var audit = audits(org, "merge");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getResourceId()).isEqualTo(String.valueOf(primary));
        assertThat(audit.get(0).getBeforeJson()).contains("Gina L");

        // merging again into another record carries the history along
        long later = create(manager, "/api/leads", m("name", "Gina Linetti"));
        JsonNode again = body(postAs(manager, "/api/leads/" + later + "/merge", m("secondaryIds", List.of(primary)))
                .andExpect(status().isOk()));
        assertThat(again.path("record").path("mergedIds").asText()).isEqualTo(s1 + "," + s2 + "," + primary);
    }

    @Test
    void leadMergeRepointsEveryChildRelationAndAppendsRecordPageBlocks() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        long primary = create(vp, "/api/leads", m("name", "Rosa Diaz"));
        long secondary = create(vp, "/api/leads", m("name", "Rosa D"));
        var children = everyChild(org, vp, "Lead", secondary);
        var untouched = child(noteRepository, Note::new, org, "relatedType", "relatedId", "Contact", secondary,
                "body", "same id, other type", "createdBy", vp.getId());

        RecordPage mine = page(org, "Lead", primary);
        block(mine.getId(), 0, "primary notes");
        RecordPage theirs = page(org, "Lead", secondary);
        PageBlock b1 = block(theirs.getId(), 0, "secondary a");
        PageBlock b2 = block(theirs.getId(), 1, "secondary b");

        JsonNode res = body(postAs(vp, "/api/leads/" + primary + "/merge", m("secondaryIds", List.of(secondary)))
                .andExpect(status().isOk()));
        assertThat(res.path("repointed").path("Activity").asInt()).isEqualTo(1);

        assertAllPointAt(children, primary);
        assertThat(noteRepository.findById(untouched.getId()).orElseThrow().getRelatedId()).isEqualTo(secondary);
        PageBlock moved1 = pageBlockRepository.findById(b1.getId()).orElseThrow();
        PageBlock moved2 = pageBlockRepository.findById(b2.getId()).orElseThrow();
        assertThat(moved1.getPageId()).isEqualTo(mine.getId());
        assertThat(moved1.getPosition()).isEqualTo(1);
        assertThat(moved2.getPosition()).isEqualTo(2);
    }

    @Test
    void mergingAConvertedLeadHandsItsOpportunityToThePrimary() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        long primary = create(vp, "/api/leads", m("name", "Charles Boyle"));
        long converted = create(vp, "/api/leads", m("name", "Charles B", "email", "boyle@99.com"));
        long oppId = body(postAs(vp, "/api/leads/" + converted + "/convert", m()).andExpect(status().isOk()))
                .path("opportunity").path("id").asLong();

        postAs(vp, "/api/leads/" + primary + "/merge", m("secondaryIds", List.of(converted))).andExpect(status().isOk());
        assertThat(opportunityRepository.findById(oppId).orElseThrow().getSourceLeadId()).isEqualTo(primary);
        Lead p = leadRepository.findById(primary).orElseThrow();
        assertThat(p.getConvertedOppId()).isEqualTo(oppId);
        assertThat(p.getStage()).isEqualTo("Won");
    }

    // ── contacts / companies ────────────────────────────────────────────────────

    @Test
    void contactMergeRepointsChildrenOpportunitiesAndAdoptsTheRecordPage() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        long primary = create(vp, "/api/contacts", m("name", "Amy Santiago", "title", "Sergeant"));
        long secondary = create(vp, "/api/contacts", m("name", "Amy S", "title", "Detective", "linkedin",
                "https://linkedin.com/in/amy"));
        var children = everyChild(org, vp, "Contact", secondary);
        long opp = create(vp, "/api/opportunities", m("name", "Precinct deal", "contactId", secondary));
        RecordPage theirs = page(org, "Contact", secondary);

        JsonNode res = body(postAs(vp, "/api/contacts/" + primary + "/merge", m("secondaryIds", List.of(secondary)))
                .andExpect(status().isOk()));
        assertThat(res.path("record").path("title").asText()).isEqualTo("Sergeant");
        assertThat(res.path("record").path("linkedin").asText()).isEqualTo("https://linkedin.com/in/amy");

        assertAllPointAt(children, primary);
        assertThat(opportunityRepository.findById(opp).orElseThrow().getContactId()).isEqualTo(primary);
        assertThat(recordPageRepository.findById(theirs.getId()).orElseThrow().getRelatedId()).isEqualTo(primary);
        assertThat(contactRepository.findById(secondary)).isEmpty();
    }

    @Test
    void companyMergeRepointsContactsLeadsOpportunitiesAndChildren() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        long primary = create(vp, "/api/companies", m("name", "Nakatomi", "industry", "Trading"));
        long secondary = create(vp, "/api/companies", m("name", "Nakatomi Corp", "website", "nakatomi.com"));
        long third = create(vp, "/api/companies", m("name", "Nakatomi Plaza", "phone", "+13105550100"));
        var children = everyChild(org, vp, "Company", third);
        long contact = create(vp, "/api/contacts", m("name", "Holly Gennero", "companyId", secondary));
        long lead = create(vp, "/api/leads", m("name", "Harry Ellis", "companyId", third));
        long opp = create(vp, "/api/opportunities", m("name", "Bearer bonds", "companyId", secondary));

        JsonNode res = body(postAs(vp, "/api/companies/" + primary + "/merge", m("secondaryIds", List.of(secondary, third)))
                .andExpect(status().isOk()));
        assertThat(res.path("record").path("website").asText()).isEqualTo("https://nakatomi.com");
        assertThat(res.path("record").path("phone").asText()).isEqualTo("+13105550100");
        assertThat(res.path("record").path("industry").asText()).isEqualTo("Trading");

        assertAllPointAt(children, primary);
        assertThat(contactRepository.findById(contact).orElseThrow().getCompanyId()).isEqualTo(primary);
        assertThat(leadRepository.findById(lead).orElseThrow().getCompanyId()).isEqualTo(primary);
        assertThat(opportunityRepository.findById(opp).orElseThrow().getCompanyId()).isEqualTo(primary);
        assertThat(companyRepository.findById(secondary)).isEmpty();
        assertThat(companyRepository.findById(third)).isEmpty();
    }

    // ── validation / permissions ────────────────────────────────────────────────

    @Test
    void mergeIsManagerUpNeedsEditOnEveryRecordAndValidatesIds() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User outsider = user(org, Role.EXECUTIVE); // not on the manager's team
        long a = create(rep, "/api/leads", m("name", "Terry Jeffords"));
        long b = create(rep, "/api/leads", m("name", "Terry J"));
        long c = create(rep, "/api/leads", m("name", "T Jeffords"));
        long d = create(rep, "/api/leads", m("name", "Terry Jeff"));
        long foreign = create(outsider, "/api/leads", m("name", "Terry Jeffords II"));

        postAs(rep, "/api/leads/" + a + "/merge", m("secondaryIds", List.of(b))).andExpect(status().isForbidden());
        postAs(manager, "/api/leads/" + a + "/merge", m("secondaryIds", List.of(foreign))).andExpect(status().isForbidden());
        postAs(manager, "/api/leads/" + a + "/merge", m("secondaryIds", List.of(b, c, d))).andExpect(status().isBadRequest());
        postAs(manager, "/api/leads/" + a + "/merge", m("secondaryIds", List.of(a))).andExpect(status().isBadRequest());
        postAs(manager, "/api/leads/" + a + "/merge", m("secondaryIds", List.of())).andExpect(status().isBadRequest());
        postAs(manager, "/api/leads/" + a + "/merge", m("secondaryIds", "x")).andExpect(status().isBadRequest());
        postAs(manager, "/api/leads/" + a + "/merge", m("secondaryIds", List.of(999_999_999L))).andExpect(status().isNotFound());

        Long otherOrg = newOrg();
        User otherVp = user(otherOrg, Role.VP_SALES);
        postAs(otherVp, "/api/leads/" + a + "/merge", m("secondaryIds", List.of(b))).andExpect(status().isNotFound());

        // nothing was touched by the refused attempts
        assertThat(leadRepository.findById(b)).isPresent();
        assertThat(audits(org, "merge")).isEmpty();

        postAs(manager, "/api/leads/" + a + "/merge", m("secondaryIds", List.of(b, c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        // a merged (trashed) record cannot be merged again
        postAs(manager, "/api/leads/" + d + "/merge", m("secondaryIds", List.of(b))).andExpect(status().isNotFound());
    }
}
