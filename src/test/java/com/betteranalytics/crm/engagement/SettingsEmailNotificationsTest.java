package com.betteranalytics.crm.engagement;

import com.betteranalytics.crm.entity.AppNotification;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** ORG_SETTINGS (AI tab), EMAIL_ACCOUNT (SMTP/IMAP), NOTIFICATION permissions. */
class SettingsEmailNotificationsTest extends EngagementTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService notificationService;

    @Test
    void aiSettingsReadableByAllEditableBySuperAdminOnly() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User vp = user(org, Role.VP_SALES);
        User support = user(org, Role.SUPPORT);

        getAs(support, "/api/settings/ai").andExpect(status().isOk())
                .andExpect(jsonPath("$.aiProvider").value("anthropic"))
                .andExpect(jsonPath("$.aiModel").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.aiKeySet").value(false));
        putAs(vp, "/api/settings/ai", Map.of("aiEnabled", true)).andExpect(status().isForbidden());
        putAs(support, "/api/settings/ai", Map.of("aiEnabled", true)).andExpect(status().isForbidden());
        deleteAs(vp, "/api/settings/ai/key").andExpect(status().isForbidden());
        putAs(admin, "/api/settings/ai", Map.of("aiProvider", "gemini")).andExpect(status().isBadRequest());

        JsonNode saved = body(putAs(admin, "/api/settings/ai",
                Map.of("aiProvider", "anthropic", "aiApiKey", "sk-ant-api03-secret-7777", "aiEnabled", true))
                .andExpect(status().isOk()));
        assertThat(saved.path("aiKeySet").asBoolean()).isTrue();
        assertThat(saved.path("aiKeyHint").asText()).endsWith("7777");
        assertThat(saved.toString()).doesNotContain("sk-ant-api03-secret-7777");
        String stored = jdbc.queryForObject("SELECT ai_api_key FROM org_settings WHERE organization_id = ?", String.class, org);
        assertThat(stored).startsWith("enc:").doesNotContain("secret-7777");

        // Other org unaffected.
        getAs(user(newOrg(), Role.SUPER_ADMIN), "/api/settings/ai").andExpect(jsonPath("$.aiKeySet").value(false));
    }

    @Test
    void emailAccountsAreAdminOnlyAndPasswordsEncrypted() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        Map<String, Object> acct = new HashMap<>();
        acct.put("emailAddress", "sales@example.com");
        acct.put("smtpHost", "127.0.0.1");
        acct.put("smtpPort", 587);
        acct.put("smtpUser", "sales@example.com");
        acct.put("smtpPassword", "hunter2-smtp-pass");
        acct.put("smtpTls", true);

        postAs(user(org, Role.MANAGER), "/api/ai-automations/email-accounts", acct).andExpect(status().isForbidden());
        postAs(user(org, Role.SUPPORT), "/api/ai-automations/email-accounts", acct).andExpect(status().isForbidden());
        getAs(user(org, Role.SUPPORT), "/api/ai-automations/email-accounts").andExpect(status().isForbidden());

        JsonNode created = body(postAs(vp, "/api/ai-automations/email-accounts", acct).andExpect(status().isCreated()));
        assertThat(created.path("smtpPasswordSet").asBoolean()).isTrue();
        assertThat(created.toString()).doesNotContain("hunter2");
        String stored = jdbc.queryForObject("SELECT smtp_password_encrypted FROM email_accounts WHERE id = ?",
                String.class, created.path("id").asLong());
        assertThat(stored).startsWith("enc:").doesNotContain("hunter2");

        postAs(vp, "/api/ai-automations/email-accounts", acct).andExpect(status().isConflict());   // same address
        acct.put("smtpPort", 22);
        acct.put("emailAddress", "other@example.com");
        postAs(vp, "/api/ai-automations/email-accounts", acct).andExpect(status().isBadRequest());

        long id = created.path("id").asLong();
        deleteAs(user(newOrg(), Role.SUPER_ADMIN), "/api/ai-automations/email-accounts/" + id).andExpect(status().isNotFound());
        assertThat(body(getAs(vp, "/api/ai-automations/email-accounts"))).hasSize(1);
        deleteAs(vp, "/api/ai-automations/email-accounts/" + id).andExpect(status().isOk());
    }

    @Test
    void notificationsArePersonalAndPushIsDisabledWithoutConfig() throws Exception {
        Long org = newOrg();
        User a = user(org, Role.EXECUTIVE);
        User b = user(org, Role.SUPPORT);
        AppNotification n = notificationService.notify(org, a.getId(), "test", "Hello", "World", null, null);

        getAs(b, "/api/notifications").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        patchAs(b, "/api/notifications/" + n.getId(), Map.of()).andExpect(status().isNotFound());
        patchAs(a, "/api/notifications/" + n.getId(), Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        getAs(b, "/api/notifications/push-status").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));

        // SUPPORT may still manage its own inbox and device tokens.
        postAs(b, "/api/notifications/read-all", Map.of()).andExpect(status().isOk());
        postAs(b, "/api/notifications/device-tokens", Map.of("token", "tok-" + System.nanoTime(), "platform", "web"))
                .andExpect(status().isOk());
    }
}
