package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.EmailConsent;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.EmailConsentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Email unsubscribe + consent.
 *
 * Public (no JWT — the signed token is the authentication):
 *   GET  /api/email/unsubscribe?t=…   confirmation page with an "Unsubscribe" button
 *                                     (a GET never changes state: mail scanners prefetch links)
 *   POST /api/email/unsubscribe?t=…   opts the address out; also the RFC 8058 one-click
 *                                     target of List-Unsubscribe-Post
 * Authenticated:
 *   GET  /api/email/consent?email=…   state + history (campaign viewers)
 *   POST /api/email/consent           manual opt-in / opt-out {email, status, reason} (campaign editors)
 */
@RestController
@RequestMapping("/api/email")
public class EmailConsentController {

    private final EmailConsentService consentService;
    private final PermissionService permissionService;
    private final CurrentUser currentUser;
    private final AuditService auditService;

    public EmailConsentController(EmailConsentService consentService, PermissionService permissionService,
                                  CurrentUser currentUser, AuditService auditService) {
        this.consentService = consentService;
        this.permissionService = permissionService;
        this.currentUser = currentUser;
        this.auditService = auditService;
    }

    @GetMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribePage(@RequestParam(name = "t", required = false) String token) {
        if (consentService.verify(token) == null) return ResponseEntity.badRequest().body(page(
                "Link not valid", "This unsubscribe link is incomplete or has been altered.", null));
        return ResponseEntity.ok(page("Unsubscribe",
                "Click the button to stop receiving marketing emails from us.", token));
    }

    @PostMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(@RequestParam(name = "t", required = false) String token) {
        EmailConsentService.TokenClaims claims = consentService.verify(token);
        if (claims == null) return ResponseEntity.badRequest().body(page(
                "Link not valid", "This unsubscribe link is incomplete or has been altered.", null));
        if (!consentService.isOptedOut(claims.organizationId(), claims.email())) {
            consentService.record(claims.organizationId(), claims.email(), EmailConsent.OPTED_OUT,
                    EmailConsentService.SOURCE_LINK, "Unsubscribe link", null, claims.campaignId());
        }
        return ResponseEntity.ok(page("You are unsubscribed", "You will not receive further marketing emails from us.", null));
    }

    @GetMapping("/consent")
    public Map<String, Object> consent(@RequestParam String email) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        String normalized = EmailConsentService.normalize(email);
        if (normalized == null) throw new IllegalArgumentException("email is not a valid address");
        Map<String, Object> view = new LinkedHashMap<>(consentService.view(actor.getOrganizationId(), normalized));
        view.put("history", consentService.history(actor.getOrganizationId(), normalized, 50));
        return view;
    }

    @PostMapping("/consent")
    public Map<String, Object> recordConsent(@RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        String email = body.get("email") instanceof String s ? s : null;
        String status = body.get("status") instanceof String s ? s : null;
        String reason = body.get("reason") instanceof String s ? s : null;
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        EmailConsent c = consentService.record(actor.getOrganizationId(), email, status,
                EmailConsentService.SOURCE_MANUAL, reason, actor.getId(), null);
        auditService.log("consent_" + c.getStatus(), "email_consent", c.getId(), c.getEmailNormalized(), null,
                Map.of("status", c.getStatus(), "reason", c.getReason()));
        Map<String, Object> view = new LinkedHashMap<>(consentService.view(actor.getOrganizationId(), c.getEmailNormalized()));
        view.put("history", consentService.history(actor.getOrganizationId(), c.getEmailNormalized(), 50));
        return view;
    }

    /** Small self-contained page; every value is a constant or a format-checked token. */
    private static String page(String title, String text, String formToken) {
        String form = formToken == null ? "" :
                "<form method=\"post\" action=\"?t=" + formToken + "\"><button type=\"submit\">Unsubscribe</button></form>";
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + title + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;background:#f6f7fb;color:#1f2430;display:flex;"
                + "min-height:100vh;align-items:center;justify-content:center;margin:0;padding:16px}"
                + "main{background:#fff;border-radius:16px;padding:32px;max-width:420px;box-shadow:0 8px 30px rgba(0,0,0,.08)}"
                + "h1{font-size:20px;margin:0 0 8px}p{margin:0 0 20px;color:#555}"
                + "button{background:#4f46e5;color:#fff;border:0;border-radius:10px;padding:10px 18px;font-size:15px;cursor:pointer}"
                + "</style></head><body><main><h1>" + title + "</h1><p>" + text + "</p>" + form + "</main></body></html>";
    }
}
