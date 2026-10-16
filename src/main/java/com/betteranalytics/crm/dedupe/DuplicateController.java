package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Duplicate review ({@code GET /api/duplicates}) and the per-organization duplicate
 * rules ({@code /api/duplicate-rules}; changing them is SUPER_ADMIN / VP_SALES).
 */
@RestController
@RequestMapping("/api")
public class DuplicateController {

    private final DuplicateDetector detector;
    private final DuplicateRuleService ruleService;
    private final DuplicateRuleRepository ruleRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;

    public DuplicateController(DuplicateDetector detector, DuplicateRuleService ruleService,
                               DuplicateRuleRepository ruleRepository, CurrentUser currentUser,
                               AuditService auditService) {
        this.detector = detector;
        this.ruleService = ruleService;
        this.ruleRepository = ruleRepository;
        this.currentUser = currentUser;
        this.auditService = auditService;
    }

    /** Candidate groups among the records the caller may view, best match first. */
    @GetMapping("/duplicates")
    public Map<String, Object> duplicates(@RequestParam(defaultValue = "lead") String type,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return detector.groups(currentUser.get(), ObjectType.parse(type), page, size);
    }

    @GetMapping("/duplicate-rules")
    public List<Map<String, Object>> rules() {
        return ruleService.all(currentUser.organizationId()).stream().map(DuplicateController::view).toList();
    }

    @PutMapping("/duplicate-rules/{objectType}")
    public Map<String, Object> update(@PathVariable String objectType, @RequestBody JsonNode body) {
        User actor = currentUser.get();
        if (actor.getRole() != Role.SUPER_ADMIN && actor.getRole() != Role.VP_SALES) {
            throw new AccessDeniedException("only a Super Admin or VP Sales can change duplicate rules");
        }
        ObjectType type = ObjectType.parse(objectType);
        DuplicateRule rule = ruleRepository.findByOrganizationIdAndObjectType(actor.getOrganizationId(), type)
                .orElseGet(() -> DuplicateRule.defaults(actor.getOrganizationId(), type));
        Map<String, Object> before = view(rule);
        if (body.hasNonNull("actionOnCreate")) {
            rule.setActionOnCreate(DuplicateAction.parse(body.get("actionOnCreate").asText(), "actionOnCreate"));
        }
        if (body.hasNonNull("actionOnEdit")) {
            rule.setActionOnEdit(DuplicateAction.parse(body.get("actionOnEdit").asText(), "actionOnEdit"));
        }
        if (body.has("enabled")) {
            if (!body.get("enabled").isBoolean()) throw new IllegalArgumentException("enabled must be true or false");
            rule.setEnabled(body.get("enabled").asBoolean());
        }
        rule.setUpdatedBy(actor.getId());
        rule.setUpdatedAt(Instant.now());
        ruleRepository.save(rule);
        Map<String, Object> after = view(rule);
        auditService.log("update", "duplicate_rules", rule.getId(), type.name(), before, after);
        return after;
    }

    static Map<String, Object> view(DuplicateRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("objectType", r.getObjectType().name());
        m.put("actionOnCreate", r.getActionOnCreate().name());
        m.put("actionOnEdit", r.getActionOnEdit().name());
        m.put("enabled", r.isEnabled());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }
}
