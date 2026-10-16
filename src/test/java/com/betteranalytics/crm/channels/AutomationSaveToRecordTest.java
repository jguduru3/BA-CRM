package com.betteranalytics.crm.channels;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.AiAutomationRepository;
import com.betteranalytics.crm.repository.NoteRepository;
import com.betteranalytics.crm.service.AiAutomationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** AI automation output is appended as a Note on the record the run is about. */
class AutomationSaveToRecordTest extends ChannelTestSupport {

    @Autowired AiAutomationService automationService;
    @Autowired AiAutomationRepository automationRepository;
    @Autowired NoteRepository noteRepository;
    @Autowired PlatformTransactionManager txManager;

    private RestTemplate ai;
    private ClientHttpRequestFactory original;
    private MockRestServiceServer server;

    @BeforeEach
    void fakeAnthropic() {
        ai = (RestTemplate) ReflectionTestUtils.getField(automationService, "aiRestTemplate");
        original = ai.getRequestFactory();
        server = MockRestServiceServer.bindTo(ai).ignoreExpectOrder(true).build();
        server.expect(ExpectedCount.manyTimes(), requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"Strategy: call Kiran on Monday."}],
                         "usage":{"input_tokens":10,"output_tokens":7}}""", MediaType.APPLICATION_JSON));
    }

    @AfterEach
    void restore() {
        ai.setRequestFactory(original);
    }

    private AiAutomation automation(Long org, Long creator, String config) {
        AiAutomation a = new AiAutomation();
        a.setOrganizationId(org);
        a.setName("Deal coach");
        a.setTriggerType("on_lead_created");
        a.setActionType("prepare_strategy");
        a.setActionConfigJson(config);
        a.setApiProvider("anthropic");
        a.setApiKeyEncrypted("sk-test");
        a.setCreatedBy(creator);
        a.setCreatedAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        return automationRepository.save(a);
    }

    private List<Note> notesOn(Long org, String type, Long id) {
        return noteRepository.findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByPinnedDescCreatedAtDesc(org, type, id);
    }

    @Test
    void outputIsSavedAsANoteOnTheLeadByDefault() {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        Lead l = lead(org, vp.getId(), "Kiran Shah", null, null);
        AiAutomation a = automation(org, vp.getId(), "{\"promptTemplate\":\"Plan for {{lead.name}}\"}");

        AiAutomationRun run = automationService.execute(a.getId(), org, null,
                Map.of("lead.id", String.valueOf(l.getId()), "lead.name", "Kiran Shah"), "event");
        assertThat(run.getStatus()).isEqualTo("completed");
        assertThat(notesOn(org, "Lead", l.getId())).singleElement().satisfies(n -> {
            assertThat(n.getTitle()).isEqualTo("AI: Deal coach");
            assertThat(n.getBody()).isEqualTo("Strategy: call Kiran on Monday.");
            assertThat(n.getCreatedBy()).isEqualTo(vp.getId());
        });
        assertThat(run.getActionResultJson()).contains("\"noteId\"").contains("Lead:" + l.getId());

        // Contact context works the same way.
        Contact c = contact(org, vp.getId(), "Leela Das", null);
        automationService.execute(a.getId(), org, vp.getId(), Map.of("contact.id", String.valueOf(c.getId())), "manual");
        assertThat(notesOn(org, "Contact", c.getId())).hasSize(1);
    }

    @Test
    void optOutNoRecordOrForeignRecordSavesNothing() {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        Lead l = lead(org, vp.getId(), "No Note", null, null);
        AiAutomation off = automation(org, vp.getId(), "{\"saveToRecord\":false}");
        automationService.execute(off.getId(), org, null, Map.of("lead.id", String.valueOf(l.getId())), "event");
        assertThat(notesOn(org, "Lead", l.getId())).isEmpty();

        AiAutomation on = automation(org, vp.getId(), "{\"saveToRecord\":true}");
        AiAutomationRun noCtx = automationService.execute(on.getId(), org, null, Map.of("task", "x"), "manual");
        assertThat(noCtx.getStatus()).isEqualTo("completed");
        assertThat(noCtx.getActionResultJson()).doesNotContain("noteId");

        Long other = newOrg();
        Lead foreign = lead(other, user(other, Role.SUPER_ADMIN).getId(), "Foreign", null, null);
        automationService.execute(on.getId(), org, null, Map.of("lead.id", String.valueOf(foreign.getId())), "event");
        assertThat(notesOn(other, "Lead", foreign.getId())).isEmpty();
        assertThat(notesOn(org, "Lead", foreign.getId())).isEmpty();
    }

    @Test
    void insideATransactionTheNoteIsWrittenAfterCommit() {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        Lead l = lead(org, vp.getId(), "Later", null, null);
        AiAutomation a = automation(org, vp.getId(), null);
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            automationService.execute(a.getId(), org, null, Map.of("lead.id", String.valueOf(l.getId())), "event");
            assertThat(notesOn(org, "Lead", l.getId())).isEmpty();
        });
        assertThat(notesOn(org, "Lead", l.getId())).hasSize(1);
    }
}
