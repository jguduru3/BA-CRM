package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.core.CrmMasks;
import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.service.AuditService;
import jakarta.persistence.EntityManager;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Merges up to two secondary records into a primary one (HubSpot / Zoho semantics):
 * <ul>
 *   <li>the primary's values win; its empty fields are filled from the secondaries, in order;</li>
 *   <li>the oldest createdAt is kept;</li>
 *   <li>every child record is re-pointed to the primary with JPQL bulk updates — activities,
 *       tasks, notes, attachments, record pages (blocks are appended when the primary already
 *       has a page), meetings, calendar events, reminders, to-dos, documents, WhatsApp
 *       messages, campaign recipients, notifications, AI jobs; plus opportunities' contactId /
 *       companyId and contacts' / leads' companyId, and a converted lead's opportunity;</li>
 *   <li>secondaries are soft-deleted with deleteReason "merged into #id", their ids recorded
 *       in the primary's merged_ids, and the whole merge is audited as "merge".</li>
 * </ul>
 * Irreversible by design (as in both vendors): there is no un-merge. The audit event keeps
 * full snapshots of the secondaries for a manual restore from the trash.
 */
@Service
public class MergeService {

    public static final int MAX_SECONDARIES = 2;

    /** Child entities linked by (relatedType, relatedId). RecordPage is handled separately (unique per record). */
    private static final List<String> RELATED_ENTITIES = List.of(
            "Activity", "Task", "Note", "Attachment", "Meeting", "CalendarEvent", "Reminder", "TodoItem",
            "Document", "WhatsAppMessage", "CampaignContact", "AppNotification");

    private static final Map<ObjectType, List<String>> FILLABLE = Map.of(
            ObjectType.LEAD, List.of("email", "phone", "companyId", "ownerId", "value", "notes", "tags", "followUpAt",
                    "address", "businessName", "category", "leadType", "industry", "geography", "employeeBand",
                    "revenueBand", "requirement", "painPoint", "technologyUsed", "decisionMaker"),
            ObjectType.CONTACT, List.of("title", "email", "phone", "companyId", "ownerId", "source", "notes", "tags",
                    "linkedin", "lastContactedAt", "categoryId", "industry", "country", "state", "natureOfBusiness"),
            ObjectType.COMPANY, List.of("industry", "size", "website", "phone", "email", "location", "revenue",
                    "notes", "ownerId"));

    /** Numeric fields whose 0 default means "not filled in". */
    private static final Set<String> ZERO_IS_EMPTY = Set.of("value", "revenue");

    private final LeadRepository leadRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final OpportunityRepository opportunityRepository;
    private final RecordPageRepository recordPageRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final CrmMasks masks;
    private final EntityManager em;

    public MergeService(LeadRepository leadRepository, ContactRepository contactRepository,
                        CompanyRepository companyRepository, OpportunityRepository opportunityRepository,
                        RecordPageRepository recordPageRepository, PermissionService permissionService,
                        AuditService auditService, CrmMasks masks, EntityManager em) {
        this.leadRepository = leadRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.opportunityRepository = opportunityRepository;
        this.recordPageRepository = recordPageRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.masks = masks;
        this.em = em;
    }

    @Transactional
    public Map<String, Object> merge(User actor, ObjectType type, Long primaryId, List<Long> secondaryIds) {
        if (!isManagerUp(actor)) throw new AccessDeniedException("only a Manager or above can merge records");
        List<Long> ids = validateIds(primaryId, secondaryIds);
        Long orgId = actor.getOrganizationId();

        Object primary = load(type, primaryId, orgId);
        List<Object> secondaries = new ArrayList<>();
        for (Long id : ids) secondaries.add(load(type, id, orgId));
        requireEdit(actor, type, primary);
        for (Object s : secondaries) requireEdit(actor, type, s);

        Object before = copy(primary);
        BeanWrapper p = new BeanWrapperImpl(primary);
        for (Object s : secondaries) {
            BeanWrapper sw = new BeanWrapperImpl(s);
            for (String field : FILLABLE.get(type)) {
                if (isEmpty(field, p.getPropertyValue(field)) && !isEmpty(field, sw.getPropertyValue(field))) {
                    p.setPropertyValue(field, sw.getPropertyValue(field));
                }
            }
            Instant created = (Instant) sw.getPropertyValue("createdAt");
            Instant mine = (Instant) p.getPropertyValue("createdAt");
            if (created != null && (mine == null || created.isBefore(mine))) p.setPropertyValue("createdAt", created);
        }

        Map<String, Integer> repointed = new LinkedHashMap<>();
        if (type == ObjectType.LEAD) adoptConversion((Lead) primary, secondaries, orgId, repointed);
        repointChildren(type, primaryId, ids, orgId, repointed);

        LinkedHashSet<String> merged = new LinkedHashSet<>(split((String) p.getPropertyValue("mergedIds")));
        Instant now = Instant.now();
        List<Object> secondarySnapshots = new ArrayList<>();
        for (Object s : secondaries) {
            BeanWrapper sw = new BeanWrapperImpl(s);
            secondarySnapshots.add(copy(s));
            merged.addAll(split((String) sw.getPropertyValue("mergedIds")));
            merged.add(String.valueOf(sw.getPropertyValue("id")));
            sw.setPropertyValue("deletedAt", now);
            sw.setPropertyValue("deletedBy", actor.getId());
            sw.setPropertyValue("deleteReason", "merged into #" + primaryId);
            sw.setPropertyValue("updatedAt", now);
        }
        p.setPropertyValue("mergedIds", String.join(",", merged));
        p.setPropertyValue("updatedAt", now);
        em.flush();
        // created_at is mapped updatable=false, so the oldest date is written with a bulk update
        em.createQuery("UPDATE " + primary.getClass().getSimpleName() + " x SET x.createdAt = :created WHERE x.id = :id")
                .setParameter("created", p.getPropertyValue("createdAt")).setParameter("id", primaryId)
                .executeUpdate();

        Map<String, Object> auditBefore = new LinkedHashMap<>();
        auditBefore.put("primary", before);
        auditBefore.put("secondaries", secondarySnapshots);
        Map<String, Object> auditAfter = new LinkedHashMap<>();
        auditAfter.put("primary", primary);
        auditAfter.put("mergedIds", ids);
        auditAfter.put("repointed", repointed);
        auditService.log("merge", type.plural, primaryId, (String) p.getPropertyValue("name"), auditBefore, auditAfter);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("record", primary instanceof Lead l ? masks.lead(actor, l) : primary);
        out.put("mergedIds", ids);
        out.put("repointed", repointed);
        return out;
    }

    // ── re-pointing ─────────────────────────────────────────────────────────────

    private void repointChildren(ObjectType type, Long primaryId, List<Long> ids, Long orgId,
                                 Map<String, Integer> counts) {
        for (String entity : RELATED_ENTITIES) {
            int n = em.createQuery("UPDATE " + entity + " x SET x.relatedId = :primary "
                            + "WHERE x.organizationId = :org AND x.relatedType = :type AND x.relatedId IN :ids")
                    .setParameter("primary", primaryId).setParameter("org", orgId)
                    .setParameter("type", type.relatedType).setParameter("ids", ids)
                    .executeUpdate();
            counts.put(entity, n);
        }
        counts.put("AiJob", em.createQuery("UPDATE AiJob x SET x.entityId = :primary "
                        + "WHERE x.organizationId = :org AND x.entityType = :type AND x.entityId IN :ids")
                .setParameter("primary", primaryId).setParameter("org", orgId)
                .setParameter("type", type.relatedType).setParameter("ids", ids).executeUpdate());
        counts.put("RecordPage", mergeRecordPages(type, primaryId, ids, orgId));

        if (type == ObjectType.CONTACT) {
            counts.put("Opportunity.contactId", bulk("UPDATE Opportunity o SET o.contactId = :primary "
                    + "WHERE o.organizationId = :org AND o.contactId IN :ids", primaryId, orgId, ids));
        }
        if (type == ObjectType.COMPANY) {
            counts.put("Opportunity.companyId", bulk("UPDATE Opportunity o SET o.companyId = :primary "
                    + "WHERE o.organizationId = :org AND o.companyId IN :ids", primaryId, orgId, ids));
            counts.put("Contact.companyId", bulk("UPDATE Contact c SET c.companyId = :primary "
                    + "WHERE c.organizationId = :org AND c.companyId IN :ids", primaryId, orgId, ids));
            counts.put("Lead.companyId", bulk("UPDATE Lead l SET l.companyId = :primary "
                    + "WHERE l.organizationId = :org AND l.companyId IN :ids", primaryId, orgId, ids));
        }
    }

    private int bulk(String jpql, Long primaryId, Long orgId, List<Long> ids) {
        return em.createQuery(jpql).setParameter("primary", primaryId).setParameter("org", orgId)
                .setParameter("ids", ids).executeUpdate();
    }

    /**
     * Record pages are unique per record: a secondary's page becomes the primary's when the
     * primary has none; otherwise its blocks are appended to the primary's page.
     */
    private int mergeRecordPages(ObjectType type, Long primaryId, List<Long> ids, Long orgId) {
        int moved = 0;
        RecordPage target = recordPageRepository
                .findByOrganizationIdAndRelatedTypeAndRelatedId(orgId, type.relatedType, primaryId).orElse(null);
        for (Long id : ids) {
            RecordPage page = recordPageRepository
                    .findByOrganizationIdAndRelatedTypeAndRelatedId(orgId, type.relatedType, id).orElse(null);
            if (page == null) continue;
            if (target == null) {
                page.setRelatedId(primaryId);
                page.setUpdatedAt(Instant.now());
                recordPageRepository.saveAndFlush(page);
                target = page;
            } else {
                Integer max = em.createQuery("SELECT MAX(b.position) FROM PageBlock b WHERE b.pageId = :page", Integer.class)
                        .setParameter("page", target.getId()).getSingleResult();
                int offset = (max == null ? -1 : max) + 1;
                em.createQuery("UPDATE PageBlock b SET b.pageId = :target, b.position = b.position + :offset "
                                + "WHERE b.pageId = :page")
                        .setParameter("target", target.getId()).setParameter("offset", offset)
                        .setParameter("page", page.getId()).executeUpdate();
            }
            moved++;
        }
        return moved;
    }

    /** A converted secondary lead hands its opportunity to an unconverted primary (one opp per lead). */
    private void adoptConversion(Lead primary, List<Object> secondaries, Long orgId, Map<String, Integer> counts) {
        int n = 0;
        if (primary.getConvertedOppId() == null
                && opportunityRepository.findBySourceLeadIdIncludingDeleted(primary.getId(), orgId).isEmpty()) {
            for (Object o : secondaries) {
                Lead s = (Lead) o;
                if (s.getConvertedOppId() == null) continue;
                primary.setConvertedOppId(s.getConvertedOppId());
                primary.setConvertedAt(s.getConvertedAt());
                primary.setStage(s.getStage());
                n = em.createQuery("UPDATE Opportunity o SET o.sourceLeadId = :primary "
                                + "WHERE o.organizationId = :org AND o.sourceLeadId = :secondary")
                        .setParameter("primary", primary.getId()).setParameter("org", orgId)
                        .setParameter("secondary", s.getId()).executeUpdate();
                break;
            }
        }
        counts.put("Opportunity.sourceLeadId", n);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static List<Long> validateIds(Long primaryId, List<Long> secondaryIds) {
        if (secondaryIds == null || secondaryIds.isEmpty()) throw new IllegalArgumentException("secondaryIds is required");
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : secondaryIds) {
            if (id == null) throw new IllegalArgumentException("secondaryIds must not contain null");
            if (id.equals(primaryId)) throw new IllegalArgumentException("a record cannot be merged into itself");
            ids.add(id);
        }
        if (ids.size() > MAX_SECONDARIES) {
            throw new IllegalArgumentException("at most " + MAX_SECONDARIES + " records can be merged into one at a time");
        }
        return List.copyOf(ids);
    }

    private Object load(ObjectType type, Long id, Long orgId) {
        Optional<?> found = switch (type) {
            case LEAD -> leadRepository.findByIdAndOrganizationId(id, orgId);
            case CONTACT -> contactRepository.findByIdAndOrganizationId(id, orgId);
            case COMPANY -> companyRepository.findByIdAndOrganizationId(id, orgId);
        };
        return found.orElseThrow(() -> new NoSuchElementException(type.relatedType.toLowerCase() + " #" + id + " not found"));
    }

    private void requireEdit(User actor, ObjectType type, Object record) {
        BeanWrapper w = new BeanWrapperImpl(record);
        permissionService.require(actor, Action.EDIT, type.resource,
                (Long) w.getPropertyValue("organizationId"), (Long) w.getPropertyValue("ownerId"));
    }

    private static Object copy(Object record) {
        Object c = BeanUtils.instantiateClass(record.getClass());
        BeanUtils.copyProperties(record, c);
        return c;
    }

    private static boolean isEmpty(String field, Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isBlank();
        if (v instanceof Number n && ZERO_IS_EMPTY.contains(field)) return n.longValue() == 0;
        return false;
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static boolean isManagerUp(User u) {
        return u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.VP_SALES || u.getRole() == Role.MANAGER;
    }
}
