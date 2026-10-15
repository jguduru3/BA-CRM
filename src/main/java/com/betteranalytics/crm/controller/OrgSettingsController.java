package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.OrgSettings;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.OrgSettingsRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.security.SecretCrypto;
import com.betteranalytics.crm.service.AiService;
import com.betteranalytics.crm.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI configuration per organization.
 *
 * GET    /api/settings/ai              — read settings (key never returned: aiKeySet + aiKeyHint)
 * PUT    /api/settings/ai              — update provider, model, key, enabled
 * DELETE /api/settings/ai/key          — clear the stored API key
 *
 * Permissions via PermissionService / ResourceType.ORG_SETTINGS: VIEW for every
 * role (so the UI knows whether AI is on), EDIT for SUPER_ADMIN only.
 */
@RestController
@RequestMapping("/api/settings")
public class OrgSettingsController {

    private static final Set<String> PROVIDERS = Set.of("anthropic", "openai");
    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    private final OrgSettingsRepository settingsRepo;
    private final CurrentUser           currentUser;
    private final PermissionService     permissionService;
    private final AuditService          auditService;

    public OrgSettingsController(OrgSettingsRepository settingsRepo, CurrentUser currentUser,
                                 PermissionService permissionService, AuditService auditService) {
        this.settingsRepo = settingsRepo;
        this.currentUser  = currentUser;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    @GetMapping("/ai")
    public ResponseEntity<Map<String, Object>> getAiSettings() {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.ORG_SETTINGS, actor.getOrganizationId(), null);
        Long orgId = actor.getOrganizationId();
        OrgSettings s = settingsRepo.findByOrganizationId(orgId).orElse(null);
        if (s == null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("aiEnabled", false);
            d.put("aiProvider", "anthropic");
            d.put("aiModel", AiService.DEFAULT_ANTHROPIC_MODEL);
            d.put("aiKeySet", false);
            d.put("aiKeyHint", null);
            return ResponseEntity.ok(d);
        }
        return ResponseEntity.ok(safeView(s));
    }

    @PutMapping("/ai")
    public ResponseEntity<Map<String, Object>> updateAiSettings(
            @RequestBody Map<String, Object> req) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.ORG_SETTINGS, actor.getOrganizationId(), null);

        Long orgId = actor.getOrganizationId();
        OrgSettings s = settingsRepo.findByOrganizationId(orgId).orElseGet(() -> {
            OrgSettings n = new OrgSettings();
            n.setOrganizationId(orgId);
            n.setAiProvider("anthropic");
            n.setAiModel(AiService.DEFAULT_ANTHROPIC_MODEL);
            n.setCreatedAt(Instant.now());
            return n;
        });
        Map<String, Object> before = safeView(s);

        if (req.get("aiEnabled") != null) {
            if (!(req.get("aiEnabled") instanceof Boolean b))
                throw new IllegalArgumentException("aiEnabled must be a boolean");
            s.setAiEnabled(b);
        }
        String previousProvider = s.getAiProvider();
        if (req.get("aiProvider") != null) {
            if (!(req.get("aiProvider") instanceof String p) || !PROVIDERS.contains(p.trim().toLowerCase()))
                throw new IllegalArgumentException("aiProvider must be 'anthropic' or 'openai'");
            s.setAiProvider(p.trim().toLowerCase());
        }
        if (req.get("aiModel") instanceof String m && !m.isBlank()) {
            if (!MODEL.matcher(m.trim()).matches()) throw new IllegalArgumentException("aiModel is not valid");
            s.setAiModel(m.trim());
        } else if (req.get("aiModel") != null && !(req.get("aiModel") instanceof String)) {
            throw new IllegalArgumentException("aiModel must be a string");
        } else if (s.getAiProvider() != null && !s.getAiProvider().equals(previousProvider)) {
            // Provider switched without a model: don't keep the other provider's model id.
            s.setAiModel(AiService.modelFor(s.getAiProvider(), s.getAiModel()));
        }
        if (req.get("aiApiKey") != null) {
            if (!(req.get("aiApiKey") instanceof String k))
                throw new IllegalArgumentException("aiApiKey must be a string");
            if (k.length() > 500) throw new IllegalArgumentException("aiApiKey is too long");
            if (!k.isBlank()) s.setAiApiKey(k.trim());
        }

        s.setUpdatedAt(Instant.now());
        settingsRepo.save(s);
        auditService.log("update", "org_settings", s.getId(), "AI settings", before, safeView(s));
        return ResponseEntity.ok(safeView(s));
    }

    @DeleteMapping("/ai/key")
    public ResponseEntity<Map<String, Object>> clearApiKey() {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.ORG_SETTINGS, actor.getOrganizationId(), null);

        Long orgId = actor.getOrganizationId();
        settingsRepo.findByOrganizationId(orgId).ifPresent(s -> {
            s.setAiApiKey(null);
            s.setAiEnabled(false);
            s.setUpdatedAt(Instant.now());
            settingsRepo.save(s);
            auditService.log("clear_ai_key", "org_settings", s.getId(), "AI settings", null, null);
        });
        return ResponseEntity.ok(Map.of("ok", true, "aiKeySet", false, "aiEnabled", false));
    }

    private Map<String, Object> safeView(OrgSettings s) {
        boolean keySet = s.getAiApiKey() != null && !s.getAiApiKey().isBlank();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aiEnabled",  s.isAiEnabled());
        String provider = s.getAiProvider() != null ? s.getAiProvider() : "anthropic";
        m.put("aiProvider", provider);
        m.put("aiModel",    AiService.modelFor(provider, s.getAiModel()));
        m.put("aiKeySet",   keySet);
        m.put("aiKeyHint",  keySet ? SecretCrypto.hint(s.getAiApiKey()) : null);
        return m;
    }
}
