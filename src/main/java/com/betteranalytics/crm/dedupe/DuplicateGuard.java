package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The duplicate rules: runs the matching rules before a lead / contact / company
 * is created or its match keys are edited, and applies the organization's action.
 * <ul>
 *   <li>ALLOW (or rule disabled) — save;</li>
 *   <li>WARN — 409 DUPLICATE_WARNING with the candidates the caller may VIEW, unless the
 *       request carries {@code ?allowDuplicate=true} or {@code X-Allow-Duplicate: true}:
 *       then it saves and {@link #afterSave} writes a "duplicate_bypass" audit event;</li>
 *   <li>BLOCK — 409 DUPLICATE_BLOCKED, no bypass. Blocking considers every record in the
 *       organization (a duplicate the caller cannot see is still a duplicate), but only
 *       candidates the caller may VIEW are listed.</li>
 * </ul>
 */
@Service
public class DuplicateGuard {

    public static final String BYPASS_PARAM = "allowDuplicate";
    public static final String BYPASS_HEADER = "X-Allow-Duplicate";

    private final DuplicateDetector detector;
    private final DuplicateRuleService rules;
    private final AuditService auditService;

    public DuplicateGuard(DuplicateDetector detector, DuplicateRuleService rules, AuditService auditService) {
        this.detector = detector;
        this.rules = rules;
        this.auditService = auditService;
    }

    /** The outcome of a check that let the save go ahead; pass it to {@link #afterSave}. */
    public record Outcome(ObjectType type, List<DuplicateCandidate> bypassed) {
        static Outcome clean(ObjectType type) { return new Outcome(type, List.of()); }
    }

    public Outcome checkCreate(User actor, ObjectType type, Probe probe) {
        return check(actor, type, probe, true);
    }

    /** Edits are only checked when a match key actually changed. */
    public Outcome checkEdit(User actor, ObjectType type, Probe before, Probe after) {
        if (!after.keysDifferFrom(before)) return Outcome.clean(type);
        return check(actor, type, after, false);
    }

    private Outcome check(User actor, ObjectType type, Probe probe, boolean creating) {
        DuplicateRule rule = rules.get(actor.getOrganizationId(), type);
        if (!rule.isEnabled()) return Outcome.clean(type);
        DuplicateAction action = creating ? rule.getActionOnCreate() : rule.getActionOnEdit();
        switch (action) {
            case ALLOW -> {
                return Outcome.clean(type);
            }
            case BLOCK -> {
                List<DuplicateCandidate> all = detector.find(actor, type, probe, false);
                if (all.isEmpty()) return Outcome.clean(type);
                throw new DuplicateFoundException(type, true,
                        all.stream().filter(c -> detector.visible(actor, type, c)).toList());
            }
            default -> {
                List<DuplicateCandidate> visible = detector.find(actor, type, probe, true);
                if (visible.isEmpty()) return Outcome.clean(type);
                if (!bypassRequested()) throw new DuplicateFoundException(type, false, visible);
                return new Outcome(type, visible);
            }
        }
    }

    /** Records the bypass of a warning once the record exists. */
    public void afterSave(Outcome outcome, Long id, String name) {
        if (outcome == null || outcome.bypassed().isEmpty()) return;
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("candidates", outcome.bypassed());
        auditService.log("duplicate_bypass", outcome.type().plural, id, name, null, after);
    }

    static boolean bypassRequested() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return false;
        HttpServletRequest req = attrs.getRequest();
        return "true".equalsIgnoreCase(req.getParameter(BYPASS_PARAM))
                || "true".equalsIgnoreCase(req.getHeader(BYPASS_HEADER));
    }
}
