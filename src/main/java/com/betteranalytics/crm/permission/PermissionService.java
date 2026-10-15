package com.betteranalytics.crm.permission;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.cache.NoopPermissionScopeCache;
import com.betteranalytics.crm.permission.cache.PermissionScopeCache;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Single decision point for all access-control checks.
 *
 * Fixed check order per spec §7 build notes:
 *   tenant → feature permission → record scope (own/team/org) → field mask
 *
 * NEW in V5:
 *   - Permission-denial audit logging (spec: "Log permission denials to
 *     Application Insights; repeated denials on export/contact fields are an
 *     early data-theft signal.")
 *   - Delegation support: a named delegate inherits the delegator's TEAM scope
 *     for the active date range (spec §7 hierarchy edge cases).
 *   - Inside Sales "own sourced" opportunity visibility (spec §5).
 *   - Activity body masking (spec §4: others see only metadata).
 *   - canEditLeadGeography() — Field Sales cannot edit geography (spec §3).
 *   - canOverrideLeadScore()  — Manager+ only (spec §3).
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final UserRepository         userRepository;
    private final TeamRepository         teamRepository;
    private final TeamMemberRepository   teamMemberRepository;
    private final DelegationRepository   delegationRepository;
    private final AuditEventRepository   auditEventRepository;
    private final PermissionScopeCache   scopeCache;

    public PermissionService(UserRepository userRepository,
                             TeamRepository teamRepository,
                             TeamMemberRepository teamMemberRepository,
                             DelegationRepository delegationRepository,
                             AuditEventRepository auditEventRepository,
                             org.springframework.beans.factory.ObjectProvider<PermissionScopeCache> scopeCache) {
        this.userRepository       = userRepository;
        this.teamRepository       = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.delegationRepository = delegationRepository;
        this.auditEventRepository = auditEventRepository;
        this.scopeCache           = scopeCache.getIfAvailable(NoopPermissionScopeCache::new);
    }

    // ── Step 1: tenant ─────────────────────────────────────────────────────────
    private void requireSameOrg(User actor, Long recordOrganizationId) {
        if (!actor.getOrganizationId().equals(recordOrganizationId)) {
            throw new AccessDeniedException("record does not belong to your organization");
        }
    }

    /**
     * Resources whose OWN/TEAM scope a delegate may inherit from a delegator.
     * Deliberately excludes personal resources (notifications, meetings, notes)
     * — a delegation hands over sales-record oversight, not someone's inbox.
     */
    private static final Set<ResourceType> DELEGABLE = EnumSet.of(
            ResourceType.LEAD, ResourceType.OPPORTUNITY, ResourceType.CONTACT,
            ResourceType.COMPANY, ResourceType.TASK, ResourceType.ACTIVITY);

    private static final String SCOPE_CACHE_PREFIX = PermissionService.class.getName() + ".scope.";

    // ── Steps 2+3: feature permission + record scope ────────────────────────────
    /**
     * recordOwnerId == null has two very different meanings, so it is resolved
     * explicitly instead of being treated as "anyone may act":
     *   - CREATE checks pass null because the record doesn't exist yet → allowed
     *     (the feature table already decided whether the role may create).
     *   - an existing record with no owner (e.g. an unassigned lead) must NOT be
     *     editable/deletable by every OWN/TEAM-scoped user — see allowsUnowned().
     * ORG-scoped checks never look at the owner, so org-level callers that pass
     * null (campaigns, custom databases, documents list) behave as before.
     */
    public boolean can(User actor, Action action, ResourceType resource,
                       Long recordOrganizationId, Long recordOwnerId) {
        if (recordOrganizationId != null) requireSameOrg(actor, recordOrganizationId);

        Scope scope = FeaturePermissionTable.lookup(actor.getRole(), action, resource);
        return switch (scope) {
            case NONE -> false;
            case ORG  -> true;
            case OWN  -> recordOwnerId == null
                    ? allowsUnowned(scope, action, resource)
                    : recordOwnerId.equals(actor.getId())
                      // H5: a delegate holding only OWN scope still inherits the
                      // delegator's team for the active date range.
                      || (DELEGABLE.contains(resource) && delegatedOwnerIds(actor).contains(recordOwnerId));
            case TEAM -> recordOwnerId == null
                    ? allowsUnowned(scope, action, resource)
                    : isInTeamScope(actor, recordOwnerId, resource);
        };
    }

    /**
     * Policy for OWN/TEAM-scoped actions on a record that has no owner.
     *   - CREATE: the null is "not created yet", not "unowned" → allow.
     *   - LEAD VIEW: unassigned-pool leads stay visible so reps can claim them.
     *   - LEAD EDIT/ASSIGN for TEAM scope (Managers): they triage the pool by
     *     assigning unowned leads; without this a Manager could never assign one.
     *   - everything else (rep edits/deletes of unowned records, Manager deletes,
     *     unowned tasks/contacts/opps...) → deny.
     */
    private boolean allowsUnowned(Scope scope, Action action, ResourceType resource) {
        if (action == Action.CREATE) return true;
        if (resource != ResourceType.LEAD) return false;
        if (action == Action.VIEW) return true;
        return scope == Scope.TEAM && (action == Action.EDIT || action == Action.ASSIGN);
    }

    public void require(User actor, Action action, ResourceType resource,
                        Long recordOrganizationId, Long recordOwnerId) {
        if (!can(actor, action, resource, recordOrganizationId, recordOwnerId)) {
            logDenial(actor, action, resource, recordOwnerId);
            throw new AccessDeniedException(
                    "not permitted: " + actor.getRole() + " cannot " + action + " this " + resource);
        }
    }

    // ── TEAM scope resolution ────────────────────────────────────────────────────
    /**
     * TEAM scope = actor's own records + direct reports (via reportsToId) +
     * anyone on teams the actor leads (via TeamMember roster) +
     * V5: anyone in scope of an active delegation where actor is the delegate.
     */
    private boolean isInTeamScope(User actor, Long recordOwnerId, ResourceType resource) {
        if (managedUserIds(actor).contains(recordOwnerId)) return true;
        return DELEGABLE.contains(resource) && delegatedOwnerIds(actor).contains(recordOwnerId);
    }

    /**
     * True when targetUserId is the actor, a direct report, on a team the actor
     * leads, or inside an active delegation the actor holds. Used by controllers
     * to keep a Manager's (re)assignments inside their own team.
     */
    public boolean isInTeamScope(User actor, Long targetUserId) {
        if (targetUserId == null) return false;
        return managedUserIds(actor).contains(targetUserId) || delegatedOwnerIds(actor).contains(targetUserId);
    }

    /**
     * Ids of the users in the actor's team scope (self + direct reports + rosters
     * of teams the actor leads) — the reporting tree used for TEAM-scoped views
     * that are about people rather than owned records, e.g. the audit log.
     */
    public Set<Long> teamScopeUserIds(User actor) {
        return managedUserIds(actor);
    }

    /** Direct reports + rosters of led teams, without delegations — "people who answer to me". */
    public boolean isManagedBy(User manager, Long targetUserId) {
        return targetUserId != null && managedUserIds(manager).contains(targetUserId);
    }

    /**
     * Who may the actor make the owner/assignee of a record?
     *   SUPER_ADMIN / VP_SALES → anyone in the org (caller validates org membership)
     *   MANAGER                → themselves or someone in their team scope
     *   everyone else          → only themselves
     */
    public boolean canAssignTo(User actor, Long targetUserId) {
        if (targetUserId == null) return false;
        if (targetUserId.equals(actor.getId())) return true;
        Role r = actor.getRole();
        if (r == Role.SUPER_ADMIN || r == Role.VP_SALES) return true;
        if (r == Role.MANAGER) return isInTeamScope(actor, targetUserId);
        return false;
    }

    /** True when the user is recorded as the leader of the team. */
    public boolean leadsTeam(Long userId, Team team) {
        return team != null && userId != null && userId.equals(team.getLeaderId());
    }

    /**
     * Self + direct reports + rosters of teams the actor leads. Computed once per
     * HTTP request per actor (M17): list endpoints call can() once per row, and
     * without the cache each row cost several queries.
     */
    private Set<Long> managedUserIds(User actor) {
        return requestCached(actor, "managed:" + actor.getId(), () -> {
            Set<Long> ids = new HashSet<>();
            ids.add(actor.getId());
            userRepository.findByReportsToId(actor.getId()).forEach(u -> ids.add(u.getId()));
            List<Long> ledTeamIds = teamRepository
                    .findByOrganizationIdAndLeaderId(actor.getOrganizationId(), actor.getId())
                    .stream().map(Team::getId).collect(Collectors.toList());
            if (!ledTeamIds.isEmpty()) {
                teamMemberRepository.findByTeamIdIn(ledTeamIds).forEach(tm -> ids.add(tm.getUserId()));
            }
            return ids;
        });
    }

    /** Union of every active delegator's scope where actor is the delegate (cached per request). */
    private Set<Long> delegatedOwnerIds(User actor) {
        return requestCached(actor, "delegated:" + actor.getId() + ":" + LocalDate.now(), () -> {
            Set<Long> ids = new HashSet<>();
            List<Delegation> delegations = delegationRepository
                    .findActiveDelegationsByDelegate(actor.getId(), LocalDate.now());
            for (Delegation d : delegations) {
                // defensive: a delegation row can only ever grant scope inside the actor's own tenant
                if (!actor.getOrganizationId().equals(d.getOrganizationId())) continue;
                ids.addAll(delegatorScope(d.getDelegatorId(), d.getTeamId(), actor.getOrganizationId()));
            }
            return ids;
        });
    }

    /**
     * Resolve the owner ids a delegator's scope covers.
     *   - Delegator must still be an active user in the same org.
     *   - If restricted to a team, the delegator must actually lead that team
     *     (or be VP/Super Admin) — otherwise a Manager could hand out access to
     *     any team just by naming it on the delegation (H4). A team-restricted
     *     delegation covers only that roster (plus the delegator's own records),
     *     not the delegator's direct reports outside the team.
     */
    private Set<Long> delegatorScope(Long delegatorId, Long restrictToTeamId, Long orgId) {
        Set<Long> ids = new HashSet<>();
        User delegator = userRepository.findByIdAndOrganizationId(delegatorId, orgId).orElse(null);
        if (delegator == null || !delegator.isActive()) return ids;
        ids.add(delegatorId);

        if (restrictToTeamId != null) {
            Team team = teamRepository.findByIdAndOrganizationId(restrictToTeamId, orgId).orElse(null);
            boolean seniorDelegator = delegator.getRole() == Role.SUPER_ADMIN || delegator.getRole() == Role.VP_SALES;
            if (team == null || !(seniorDelegator || leadsTeam(delegatorId, team))) return ids;
            teamMemberRepository.findByTeamId(team.getId()).forEach(tm -> ids.add(tm.getUserId()));
            return ids;
        }

        userRepository.findByReportsToId(delegatorId).forEach(u -> ids.add(u.getId()));
        List<Long> ledTeamIds = teamRepository.findByOrganizationIdAndLeaderId(orgId, delegatorId)
                .stream().map(Team::getId).collect(Collectors.toList());
        if (!ledTeamIds.isEmpty()) {
            teamMemberRepository.findByTeamIdIn(ledTeamIds).forEach(tm -> ids.add(tm.getUserId()));
        }
        return ids;
    }

    /**
     * Request-scoped memoisation via RequestContextHolder, on top of the
     * optional cross-request {@link PermissionScopeCache} (Redis, ~60 s,
     * evicted per organization on team/user/delegation changes). Outside an
     * HTTP request (schedulers, async jobs) there is no request scope, so we
     * just compute — correctness never depends on either cache.
     */
    @SuppressWarnings("unchecked")
    private Set<Long> requestCached(User actor, String key, Supplier<Set<Long>> compute) {
        Supplier<Set<Long>> shared = () -> scopeCache.get(actor.getOrganizationId(), key, compute);
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return shared.get();
        String attr = SCOPE_CACHE_PREFIX + key;
        Object cached = attrs.getAttribute(attr, RequestAttributes.SCOPE_REQUEST);
        if (cached instanceof Set<?> set) return (Set<Long>) set;
        Set<Long> value = Collections.unmodifiableSet(shared.get());
        attrs.setAttribute(attr, value, RequestAttributes.SCOPE_REQUEST);
        return value;
    }

    // ── V5 Special: Inside Sales "own sourced" opportunity visibility ────────────
    /**
     * Inside Sales can view (but not edit/delete) opportunities that came from
     * leads they owned at conversion time, regardless of who owns the opportunity.
     * Returns false for all other roles (they use the main can() path).
     */
    public boolean canViewSourcedOpportunity(User actor, Opportunity opp) {
        if (actor.getRole() != Role.INSIDE_SALES) return false;
        return actor.getId().equals(opp.getSourcedByUserId());
    }

    // ── Step 4: field masks ──────────────────────────────────────────────────────
    // Six rules from the spec's §7 build notes ("Pull into Phase 1"):

    /** Lead.source locked after creation for Inside/Field Sales. */
    public boolean canEditLeadSource(User actor, boolean isCreating) {
        if (isCreating) return true;
        return actor.getRole() != Role.INSIDE_SALES && actor.getRole() != Role.FIELD_SALES;
    }

    /** Lead.ownerId (Assigned salesperson) is read-only below Manager. */
    public boolean canEditLeadOwner(User actor) {
        Role r = actor.getRole();
        return r == Role.SUPER_ADMIN || r == Role.VP_SALES || r == Role.MANAGER;
    }

    /** Lead.geography — Field Sales can VIEW but not EDIT (a change re-routes the lead). */
    public boolean canEditLeadGeography(User actor) {
        return actor.getRole() != Role.FIELD_SALES;
    }

    /** Lead contact info masked for Inside Sales after the lead is handed off. */
    public boolean canViewLeadContactInfo(User actor, Lead lead) {
        if (actor.getRole() != Role.INSIDE_SALES) return true;
        return lead.getConvertedOppId() == null;
    }

    /** Opportunity.value hidden from Inside Sales entirely. */
    public boolean canViewOpportunityValue(User actor) {
        return actor.getRole() != Role.INSIDE_SALES;
    }

    /** Opportunity.probability is stage-driven; only Manager+ can set it directly. */
    public boolean canEditOpportunityProbability(User actor) {
        Role r = actor.getRole();
        return r == Role.SUPER_ADMIN || r == Role.VP_SALES || r == Role.MANAGER;
    }

    public boolean canViewOpportunityProbability(User actor) {
        return actor.getRole() != Role.INSIDE_SALES;
    }

    /** Opportunity.poStatus — confirmed by Manager+; reps upload PO doc only. */
    public boolean canEditOpportunityPoStatus(User actor) {
        Role r = actor.getRole();
        return r == Role.SUPER_ADMIN || r == Role.VP_SALES || r == Role.MANAGER;
    }

    public boolean canViewOpportunityPoStatus(User actor) {
        return actor.getRole() != Role.INSIDE_SALES;
    }

    /** Lead score override — Manager only (Super Admin for admin fixes). */
    public boolean canOverrideLeadScore(User actor) {
        Role r = actor.getRole();
        return r == Role.SUPER_ADMIN || r == Role.VP_SALES || r == Role.MANAGER;
    }

    // ── V5: Activity body masking (spec §4) ────────────────────────────────────
    /**
     * Full body visible to: author, record owner (resolved in controller),
     * Manager, VP Sales, Super Admin.
     * Others (Executive, Inside Sales, Field Sales who didn't author) see
     * only metadata: type, outcome, scheduled/completed timestamps.
     * Simplified: reps see full content of activities they authored; otherwise
     * body and subject are redacted.
     */
    public boolean canViewActivityBody(User actor, Activity activity) {
        Role r = actor.getRole();
        if (r == Role.SUPER_ADMIN || r == Role.VP_SALES || r == Role.MANAGER) return true;
        // Rep sees their own activities in full; other reps' content is metadata-only
        return actor.getId().equals(activity.getUserId());
    }

    public Activity maskActivityForResponse(User actor, Activity activity) {
        if (activity == null || canViewActivityBody(actor, activity)) return activity;
        Activity masked = shallowCopyActivity(activity);
        masked.setBody(null);
        masked.setSubject("[restricted]");
        return masked;
    }

    public List<Activity> maskActivitiesForResponse(User actor, List<Activity> list) {
        return list.stream().map(a -> maskActivityForResponse(actor, a)).collect(Collectors.toList());
    }

    // ── Response masking ─────────────────────────────────────────────────────────
    public Lead maskLeadForResponse(User actor, Lead lead) {
        if (lead == null) return null;
        if (!canViewLeadContactInfo(actor, lead)) {
            Lead masked = shallowCopyLead(lead);
            masked.setEmail(lead.getEmail() == null ? null : "••••••••");
            masked.setPhone(lead.getPhone() == null ? null : "••••••••");
            return masked;
        }
        return lead;
    }

    public Opportunity maskOpportunityForResponse(User actor, Opportunity opp) {
        if (opp == null) return null;
        boolean maskValue       = !canViewOpportunityValue(actor);
        boolean maskProbability = !canViewOpportunityProbability(actor);
        boolean maskPoStatus    = !canViewOpportunityPoStatus(actor);
        if (!maskValue && !maskProbability && !maskPoStatus) return opp;

        Opportunity masked = shallowCopyOpportunity(opp);
        if (maskValue)       masked.setValue(null);
        if (maskProbability) masked.setProbability(null);
        if (maskPoStatus)    masked.setPoStatus(null);
        return masked;
    }

    /**
     * Inside Sales viewing a "sourced" opportunity gets a more aggressively
     * masked copy: only id, name, stage, service/product, competitors,
     * current vendor, sourceLeadId are visible. Financials and dates are out.
     */
    public Opportunity maskSourcedOpportunityForInsideSales(Opportunity opp) {
        Opportunity masked = new Opportunity();
        masked.setId(opp.getId());
        masked.setOrganizationId(opp.getOrganizationId());
        masked.setName(opp.getName());
        masked.setStage(opp.getStage());
        masked.setServiceProduct(opp.getServiceProduct());
        masked.setCompetitors(opp.getCompetitors());
        masked.setCurrentVendor(opp.getCurrentVendor());
        masked.setSourceLeadId(opp.getSourceLeadId());
        masked.setSourcedByUserId(opp.getSourcedByUserId());
        // Value, probability, closeDate, poStatus, proposal, negotiation — all null (hidden)
        return masked;
    }

    public List<Lead> maskLeadsForResponse(User actor, List<Lead> leads) {
        return leads.stream().map(l -> maskLeadForResponse(actor, l)).collect(Collectors.toList());
    }

    public List<Opportunity> maskOpportunitiesForResponse(User actor, List<Opportunity> opps) {
        return opps.stream().map(o -> maskOpportunityForResponse(actor, o)).collect(Collectors.toList());
    }

    // ── Denial logging ───────────────────────────────────────────────────────────
    /**
     * Logs every permission denial to the audit_events table.
     * Repeated denials on export or contact fields are a data-theft signal
     * (spec §7 build notes).
     */
    private void logDenial(User actor, Action action, ResourceType resource, Long targetOwnerId) {
        try {
            AuditEvent event = new AuditEvent();
            event.setOrganizationId(actor.getOrganizationId());
            event.setUserId(actor.getId());
            event.setUserName(actor.getName());
            event.setAction("permission_denied");
            event.setResource(resource.name().toLowerCase());
            event.setResourceId(targetOwnerId == null ? null : String.valueOf(targetOwnerId));
            event.setResourceName(action.name());
            event.setAfterJson("{\"role\":\"" + actor.getRole() + "\",\"action\":\"" + action + "\"}");
            event.setCreatedAt(Instant.now());
            auditEventRepository.save(event);
        } catch (Exception ex) {
            // Never let logging blow up the actual request
            log.warn("Failed to log permission denial for user={} action={} resource={}: {}",
                    actor.getId(), action, resource, ex.getMessage());
        }
        log.warn("PERMISSION_DENIED user={} role={} action={} resource={} targetOwner={}",
                actor.getId(), actor.getRole(), action, resource, targetOwnerId);
    }

    // ── Shallow copy helpers ─────────────────────────────────────────────────────
    private Lead shallowCopyLead(Lead l) {
        Lead c = new Lead();
        c.setId(l.getId()); c.setOrganizationId(l.getOrganizationId()); c.setName(l.getName());
        c.setEmail(l.getEmail()); c.setPhone(l.getPhone()); c.setCompanyId(l.getCompanyId());
        c.setOwnerId(l.getOwnerId()); c.setStage(l.getStage()); c.setSource(l.getSource());
        c.setPriority(l.getPriority()); c.setValue(l.getValue()); c.setNotes(l.getNotes());
        c.setTags(l.getTags()); c.setConvertedAt(l.getConvertedAt()); c.setConvertedOppId(l.getConvertedOppId());
        c.setFollowUpAt(l.getFollowUpAt()); c.setAddress(l.getAddress()); c.setBusinessName(l.getBusinessName());
        c.setCategory(l.getCategory()); c.setLeadType(l.getLeadType()); c.setIndustry(l.getIndustry());
        c.setGeography(l.getGeography()); c.setEmployeeBand(l.getEmployeeBand()); c.setRevenueBand(l.getRevenueBand());
        c.setRequirement(l.getRequirement()); c.setPainPoint(l.getPainPoint());
        c.setTechnologyUsed(l.getTechnologyUsed()); c.setDecisionMaker(l.getDecisionMaker());
        c.setLeadScore(l.getLeadScore()); c.setLeadScoreOverrideReason(l.getLeadScoreOverrideReason());
        c.setFollowUpRescheduleCount(l.getFollowUpRescheduleCount());
        c.setCreatedAt(l.getCreatedAt()); c.setUpdatedAt(l.getUpdatedAt());
        return c;
    }

    private Opportunity shallowCopyOpportunity(Opportunity o) {
        Opportunity c = new Opportunity();
        c.setId(o.getId()); c.setOrganizationId(o.getOrganizationId()); c.setName(o.getName());
        c.setCompanyId(o.getCompanyId()); c.setContactId(o.getContactId()); c.setOwnerId(o.getOwnerId());
        c.setStage(o.getStage()); c.setValue(o.getValue()); c.setProbability(o.getProbability());
        c.setCloseDate(o.getCloseDate()); c.setNotes(o.getNotes()); c.setServiceProduct(o.getServiceProduct());
        c.setCompetitors(o.getCompetitors()); c.setCurrentVendor(o.getCurrentVendor());
        c.setProposalStatus(o.getProposalStatus()); c.setNegotiationStatus(o.getNegotiationStatus());
        c.setPoStatus(o.getPoStatus()); c.setSourceLeadId(o.getSourceLeadId());
        c.setSourcedByUserId(o.getSourcedByUserId());
        c.setCreatedAt(o.getCreatedAt()); c.setUpdatedAt(o.getUpdatedAt());
        return c;
    }

    private Activity shallowCopyActivity(Activity a) {
        Activity c = new Activity();
        c.setId(a.getId()); c.setOrganizationId(a.getOrganizationId()); c.setType(a.getType());
        c.setSubject(a.getSubject()); c.setBody(a.getBody()); c.setUserId(a.getUserId());
        c.setRelatedType(a.getRelatedType()); c.setRelatedId(a.getRelatedId());
        c.setOutcome(a.getOutcome()); c.setScheduledAt(a.getScheduledAt());
        c.setCompletedAt(a.getCompletedAt()); c.setNextFollowUpAt(a.getNextFollowUpAt());
        c.setCorrectionForId(a.getCorrectionForId()); c.setDurationMinutes(a.getDurationMinutes());
        c.setCreatedAt(a.getCreatedAt());
        return c;
    }
}
