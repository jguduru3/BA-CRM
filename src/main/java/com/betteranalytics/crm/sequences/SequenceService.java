package com.betteranalytics.crm.sequences;

import com.betteranalytics.crm.entity.Contact;
import com.betteranalytics.crm.entity.EmailAccount;
import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.ContactRepository;
import com.betteranalytics.crm.repository.EmailAccountRepository;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.repository.WhatsAppAccountRepository;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.PhoneNumbers;
import com.betteranalytics.crm.service.WhatsAppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Sequence CRUD (Manager+, ResourceType.SEQUENCE) and enrollment management
 * (EDIT permission on the enrolled Lead / Contact).
 */
@Service
public class SequenceService {

    static final int MAX_STEPS = 30;
    static final int MAX_TEMPLATE_PARAMS = 10;
    static final Set<String> CHANNELS = Set.of(SequenceStep.EMAIL, SequenceStep.WHATSAPP, SequenceStep.TASK);
    static final Set<String> STATUSES = Set.of(SequenceEnrollment.ACTIVE, SequenceEnrollment.PAUSED,
            SequenceEnrollment.RESPONDED, SequenceEnrollment.COMPLETED, SequenceEnrollment.STOPPED,
            SequenceEnrollment.FAILED);

    private final SequenceRepository sequenceRepo;
    private final SequenceStepRepository stepRepo;
    private final SequenceEnrollmentRepository enrollmentRepo;
    private final SequenceStepExecutionRepository execRepo;
    private final SequenceEnrollmentEventRepository eventRepo;
    private final LeadRepository leadRepo;
    private final ContactRepository contactRepo;
    private final EmailAccountRepository emailAccountRepo;
    private final WhatsAppAccountRepository waAccountRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final SequenceEngine engine;
    private final ObjectMapper objectMapper;

    public SequenceService(SequenceRepository sequenceRepo, SequenceStepRepository stepRepo,
                           SequenceEnrollmentRepository enrollmentRepo, SequenceStepExecutionRepository execRepo,
                           SequenceEnrollmentEventRepository eventRepo, LeadRepository leadRepo,
                           ContactRepository contactRepo, EmailAccountRepository emailAccountRepo,
                           WhatsAppAccountRepository waAccountRepo, PermissionService permissionService,
                           AuditService auditService, SequenceEngine engine, ObjectMapper objectMapper) {
        this.sequenceRepo = sequenceRepo;
        this.stepRepo = stepRepo;
        this.enrollmentRepo = enrollmentRepo;
        this.execRepo = execRepo;
        this.eventRepo = eventRepo;
        this.leadRepo = leadRepo;
        this.contactRepo = contactRepo;
        this.emailAccountRepo = emailAccountRepo;
        this.waAccountRepo = waAccountRepo;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    // ── Sequences ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(User actor) {
        Long orgId = actor.getOrganizationId();
        permissionService.require(actor, Action.VIEW, ResourceType.SEQUENCE, orgId, null);
        List<Sequence> seqs = sequenceRepo.findByOrganizationIdOrderByNameAsc(orgId);
        Map<Long, List<SequenceStep>> steps = new HashMap<>();
        if (!seqs.isEmpty()) {
            for (SequenceStep s : stepRepo.findBySequenceIdInOrderByStepIndexAsc(seqs.stream().map(Sequence::getId).toList())) {
                steps.computeIfAbsent(s.getSequenceId(), k -> new ArrayList<>()).add(s);
            }
        }
        Map<Long, Map<String, Long>> counts = new HashMap<>();
        for (Object[] row : enrollmentRepo.countBySequenceAndStatus(orgId)) {
            counts.computeIfAbsent((Long) row[0], k -> new TreeMap<>()).put((String) row[1], ((Number) row[2]).longValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Sequence s : seqs) out.add(view(s, steps.getOrDefault(s.getId(), List.of()), counts.get(s.getId())));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(User actor, Long id) {
        Sequence s = requireSequence(actor, id);
        permissionService.require(actor, Action.VIEW, ResourceType.SEQUENCE, s.getOrganizationId(), null);
        return view(s, stepRepo.findBySequenceIdOrderByStepIndexAsc(id), countsFor(s));
    }

    @Transactional
    public Map<String, Object> create(User actor, Map<String, Object> body) {
        Long orgId = actor.getOrganizationId();
        permissionService.require(actor, Action.CREATE, ResourceType.SEQUENCE, orgId, null);
        Sequence s = new Sequence();
        s.setOrganizationId(orgId);
        s.setCreatedBy(actor.getId());
        s.setCreatedAt(Instant.now());
        applyFields(actor, s, body, true);
        List<SequenceStep> parsed = parseSteps(body.get("steps"));
        s.setUpdatedAt(Instant.now());
        s = sequenceRepo.saveAndFlush(s);
        List<SequenceStep> saved = saveSteps(s.getId(), parsed);
        Map<String, Object> v = view(s, saved, null);
        auditService.log("create", "sequences", s.getId(), s.getName(), null, v);
        return v;
    }

    @Transactional
    public Map<String, Object> update(User actor, Long id, Map<String, Object> body) {
        Sequence s = requireSequence(actor, id);
        permissionService.require(actor, Action.EDIT, ResourceType.SEQUENCE, s.getOrganizationId(), null);
        Map<String, Object> before = view(s, stepRepo.findBySequenceIdOrderByStepIndexAsc(id), null);
        if (body.containsKey("version") && body.get("version") instanceof Number n && n.longValue() != s.getVersion()) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Sequence.class, id);
        }
        applyFields(actor, s, body, false);
        List<SequenceStep> steps;
        if (body.containsKey("steps")) {
            List<SequenceStep> parsed = parseSteps(body.get("steps"));
            stepRepo.deleteBySequenceId(id);
            steps = saveSteps(id, parsed);
        } else {
            steps = stepRepo.findBySequenceIdOrderByStepIndexAsc(id);
        }
        s.setUpdatedAt(Instant.now());
        s = sequenceRepo.saveAndFlush(s);
        Map<String, Object> v = view(s, steps, countsFor(s));
        auditService.log("update", "sequences", s.getId(), s.getName(), before, v);
        return v;
    }

    @Transactional
    public void delete(User actor, Long id) {
        Sequence s = requireSequence(actor, id);
        permissionService.require(actor, Action.DELETE, ResourceType.SEQUENCE, s.getOrganizationId(), null);
        Instant now = Instant.now();
        for (SequenceEnrollment e : enrollmentRepo.findBySequenceIdAndStatusIn(id, SequenceEngine.LIVE)) {
            SequenceEnrollment locked = enrollmentRepo.lockById(e.getId()).orElseThrow();
            if (!locked.isLive()) continue;
            locked.finish(SequenceEnrollment.STOPPED, "Sequence was deleted", now);
            engine.event(locked, SequenceEnrollment.STOPPED, null, null, "Sequence was deleted", actor.getId(), now);
        }
        s.setDeletedAt(now);
        s.setDeletedBy(actor.getId());
        s.setActive(false);
        s.setUpdatedAt(now);
        sequenceRepo.save(s);
        auditService.log("delete", "sequences", s.getId(), s.getName(), Map.of("id", s.getId(), "name", s.getName()), null);
    }

    // ── Enrollments ─────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> enroll(User actor, Long sequenceId, Map<String, Object> body) {
        Sequence s = requireSequence(actor, sequenceId);
        permissionService.require(actor, Action.VIEW, ResourceType.SEQUENCE, s.getOrganizationId(), null);
        String type = relatedType(body.get("relatedType"));
        Long relatedId = longValue(body.get("relatedId"), "relatedId");
        if (!s.isActive()) throw new IllegalStateException("This sequence is inactive; activate it before enrolling");
        List<SequenceStep> steps = stepRepo.findBySequenceIdOrderByStepIndexAsc(sequenceId);
        if (steps.isEmpty()) throw new IllegalArgumentException("This sequence has no steps");

        Target t = requireTarget(actor, type, relatedId, Action.EDIT);
        String key = SequenceEnrollment.activeKeyFor(sequenceId, type, relatedId);
        if (!enrollmentRepo.findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
                actor.getOrganizationId(), type, relatedId).stream()
                .filter(e -> e.getSequenceId().equals(sequenceId)).filter(SequenceEnrollment::isLive).toList().isEmpty()) {
            throw new IllegalStateException("This " + type.toLowerCase(Locale.ROOT) + " is already enrolled in this sequence");
        }

        Instant now = Instant.now();
        SequenceEnrollment e = new SequenceEnrollment();
        e.setOrganizationId(actor.getOrganizationId());
        e.setSequenceId(sequenceId);
        e.setRelatedType(type);
        e.setRelatedId(relatedId);
        e.setOwnerUserId(t.ownerId != null ? t.ownerId : actor.getId());
        e.setEnrolledBy(actor.getId());
        e.setStatus(SequenceEnrollment.ACTIVE);
        e.setCurrentStep(0);
        e.setNextRunAt(now.plus(steps.get(0).delay()));
        e.setContactEmail(SequenceEngine.normalizeEmail(t.email));
        e.setContactPhone(PhoneNumbers.tryNormalize(t.phone));
        e.setActiveKey(key);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        try {
            e = enrollmentRepo.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("This " + type.toLowerCase(Locale.ROOT) + " is already enrolled in this sequence");
        }
        engine.event(e, "ENROLLED", null, null, "Enrolled in \"" + s.getName() + "\"", actor.getId(), now);
        Map<String, Object> v = enrollmentView(e, s.getName(), t.name, steps.size());
        auditService.log("create", "sequence_enrollments", e.getId(), t.name, null, v);
        return v;
    }

    @Transactional
    public Map<String, Object> transition(User actor, Long enrollmentId, String op) {
        SequenceEnrollment existing = enrollmentRepo.findByIdAndOrganizationId(enrollmentId, actor.getOrganizationId())
                .orElseThrow(() -> new NoSuchElementException("Enrollment not found"));
        requireManage(actor, existing);
        SequenceEnrollment e = enrollmentRepo.lockById(enrollmentId).orElseThrow();
        Instant now = Instant.now();
        String before = e.getStatus();
        switch (op) {
            case "pause" -> {
                if (!SequenceEnrollment.ACTIVE.equals(e.getStatus()))
                    throw new IllegalStateException("Only an active enrollment can be paused");
                e.setStatus(SequenceEnrollment.PAUSED);
                e.setUpdatedAt(now);
                engine.event(e, "PAUSED", null, null, null, actor.getId(), now);
            }
            case "resume" -> {
                if (!SequenceEnrollment.PAUSED.equals(e.getStatus()))
                    throw new IllegalStateException("Only a paused enrollment can be resumed");
                e.setStatus(SequenceEnrollment.ACTIVE);
                if (e.getNextRunAt() == null || e.getNextRunAt().isBefore(now)) e.setNextRunAt(now);
                e.setUpdatedAt(now);
                engine.event(e, "RESUMED", null, null, null, actor.getId(), now);
            }
            case "stop" -> {
                if (!e.isLive()) throw new IllegalStateException("This enrollment has already ended");
                e.finish(SequenceEnrollment.STOPPED, "Unenrolled by " + actor.getName(), now);
                engine.event(e, SequenceEnrollment.STOPPED, null, null, "Unenrolled by " + actor.getName(), actor.getId(), now);
            }
            default -> throw new IllegalArgumentException("unknown operation");
        }
        Map<String, Object> v = enrollmentView(e, sequenceName(e.getSequenceId()), recordName(e), stepCount(e.getSequenceId()));
        auditService.log("update", "sequence_enrollments", e.getId(), String.valueOf(v.get("recordName")),
                Map.of("status", before), Map.of("status", e.getStatus()));
        return v;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> enrollmentsForSequence(User actor, Long sequenceId, String status, Integer limit) {
        Sequence s = requireSequence(actor, sequenceId);
        permissionService.require(actor, Action.VIEW, ResourceType.SEQUENCE, s.getOrganizationId(), null);
        int size = Math.max(1, Math.min(500, limit == null ? 200 : limit));
        List<SequenceEnrollment> rows;
        if (status != null && !status.isBlank()) {
            String st = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(st)) throw new IllegalArgumentException("unknown status " + status);
            rows = enrollmentRepo.findBySequenceIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
                    sequenceId, actor.getOrganizationId(), st, PageRequest.of(0, size));
        } else {
            rows = enrollmentRepo.findBySequenceIdAndOrganizationIdOrderByCreatedAtDesc(
                    sequenceId, actor.getOrganizationId(), PageRequest.of(0, size));
        }
        boolean manager = permissionService.can(actor, Action.EDIT, ResourceType.SEQUENCE, actor.getOrganizationId(), null);
        if (!manager) {
            rows = rows.stream().filter(e -> permissionService.can(actor, Action.VIEW, resourceOf(e.getRelatedType()),
                    e.getOrganizationId(), e.getOwnerUserId())).toList();
        }
        Map<String, String> names = recordNames(rows);
        int steps = (int) stepRepo.countBySequenceId(sequenceId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SequenceEnrollment e : rows) {
            out.add(enrollmentView(e, s.getName(), names.get(e.getRelatedType() + ":" + e.getRelatedId()), steps));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> enrollmentsForRecord(User actor, String relatedType, Long relatedId) {
        String type = relatedType(relatedType);
        Target t = requireTarget(actor, type, relatedId, Action.VIEW);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SequenceEnrollment e : enrollmentRepo.findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
                actor.getOrganizationId(), type, relatedId)) {
            out.add(enrollmentView(e, sequenceName(e.getSequenceId()), t.name, stepCount(e.getSequenceId())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> enrollmentDetail(User actor, Long enrollmentId) {
        SequenceEnrollment e = enrollmentRepo.findByIdAndOrganizationId(enrollmentId, actor.getOrganizationId())
                .orElseThrow(() -> new NoSuchElementException("Enrollment not found"));
        boolean manager = permissionService.can(actor, Action.EDIT, ResourceType.SEQUENCE, actor.getOrganizationId(), null);
        if (!manager && !permissionService.can(actor, Action.VIEW, resourceOf(e.getRelatedType()),
                e.getOrganizationId(), e.getOwnerUserId())) {
            throw new AccessDeniedException("not permitted to view this enrollment");
        }
        Map<String, Object> v = enrollmentView(e, sequenceName(e.getSequenceId()), recordName(e), stepCount(e.getSequenceId()));
        List<Map<String, Object>> events = new ArrayList<>();
        for (SequenceEnrollmentEvent ev : eventRepo.findByEnrollmentIdOrderByCreatedAtAscIdAsc(enrollmentId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ev.getId());
            m.put("type", ev.getEventType());
            m.put("stepIndex", ev.getStepIndex());
            m.put("channel", ev.getChannel());
            m.put("detail", ev.getDetail());
            m.put("actorId", ev.getActorId());
            m.put("createdAt", ev.getCreatedAt());
            events.add(m);
        }
        List<Map<String, Object>> execs = new ArrayList<>();
        for (SequenceStepExecution x : execRepo.findByEnrollmentIdOrderByStepIndexAsc(enrollmentId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepIndex", x.getStepIndex());
            m.put("channel", x.getChannel());
            m.put("status", x.getStatus());
            m.put("detail", x.getDetail());
            m.put("taskId", x.getTaskId());
            m.put("createdAt", x.getCreatedAt());
            m.put("completedAt", x.getCompletedAt());
            execs.add(m);
        }
        v.put("events", events);
        v.put("executions", execs);
        return v;
    }

    // ── Validation / binding ────────────────────────────────────────────────

    private void applyFields(User actor, Sequence s, Map<String, Object> body, boolean creating) {
        if (creating || body.containsKey("name")) {
            Object n = body.get("name");
            if (!(n instanceof String name) || name.isBlank()) throw new IllegalArgumentException("name is required");
            if (name.trim().length() > 200) throw new IllegalArgumentException("name must be at most 200 characters");
            s.setName(name.trim());
        }
        if (body.containsKey("description")) {
            Object d = body.get("description");
            String desc = d == null ? null : String.valueOf(d).trim();
            if (desc != null && desc.length() > 1000) throw new IllegalArgumentException("description must be at most 1000 characters");
            s.setDescription(desc == null || desc.isEmpty() ? null : desc);
        }
        if (body.containsKey("active")) {
            if (!(body.get("active") instanceof Boolean b)) throw new IllegalArgumentException("active must be true or false");
            s.setActive(b);
        }
        if (body.containsKey("emailAccountId")) {
            Long id = body.get("emailAccountId") == null ? null : longValue(body.get("emailAccountId"), "emailAccountId");
            if (id != null) {
                EmailAccount a = emailAccountRepo.findByIdAndOrganizationId(id, actor.getOrganizationId())
                        .orElseThrow(() -> new IllegalArgumentException("email account " + id + " not found in this organization"));
                boolean own = actor.getId().equals(a.getUserId());
                if (!own && !permissionService.can(actor, Action.VIEW, ResourceType.EMAIL_ACCOUNT, actor.getOrganizationId(), null)) {
                    throw new AccessDeniedException("you can only send a sequence from your own mailbox");
                }
            }
            s.setEmailAccountId(id);
        }
        if (body.containsKey("whatsappAccountId")) {
            Long id = body.get("whatsappAccountId") == null ? null : longValue(body.get("whatsappAccountId"), "whatsappAccountId");
            if (id != null && waAccountRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).isEmpty()) {
                throw new IllegalArgumentException("WhatsApp account " + id + " not found in this organization");
            }
            s.setWhatsappAccountId(id);
        }
    }

    private List<SequenceStep> parseSteps(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("steps must be an array");
        if (list.size() > MAX_STEPS) throw new IllegalArgumentException("a sequence may have at most " + MAX_STEPS + " steps");
        List<SequenceStep> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> m)) throw new IllegalArgumentException("steps[" + i + "] must be an object");
            String where = "steps[" + i + "].";
            SequenceStep st = new SequenceStep();
            st.setStepIndex(i);
            Object ch = m.get("channel");
            String channel = ch == null ? null : String.valueOf(ch).trim().toUpperCase(Locale.ROOT);
            if (channel == null || !CHANNELS.contains(channel))
                throw new IllegalArgumentException(where + "channel must be EMAIL, WHATSAPP or TASK");
            st.setChannel(channel);
            st.setDelayDays(intIn(m.get("delayDays"), 0, 365, where + "delayDays"));
            st.setDelayHours(intIn(m.get("delayHours"), 0, 23 * 365, where + "delayHours"));
            String subject = str(m.get("subject"));
            String body = str(m.get("body"));
            if (subject != null && subject.length() > 300) throw new IllegalArgumentException(where + "subject must be at most 300 characters");
            if (body != null && body.length() > 20_000) throw new IllegalArgumentException(where + "body must be at most 20000 characters");
            switch (channel) {
                case SequenceStep.EMAIL -> {
                    if (subject == null) throw new IllegalArgumentException(where + "subject is required for an email step");
                    if (body == null) throw new IllegalArgumentException(where + "body is required for an email step");
                }
                case SequenceStep.TASK -> {
                    if (subject == null) throw new IllegalArgumentException(where + "subject (the task title) is required for a task step");
                }
                case SequenceStep.WHATSAPP -> {
                    String tn = str(m.get("waTemplateName"));
                    try {
                        st.setWaTemplateName(WhatsAppService.validateTemplateName(tn));
                        String lang = str(m.get("waTemplateLanguage"));
                        st.setWaTemplateLanguage(WhatsAppService.validateLanguage(lang));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(where + e.getMessage());
                    }
                    Object params = m.get("waTemplateParams");
                    List<String> p;
                    if (params instanceof List<?> pl) {
                        p = new ArrayList<>();
                        for (Object o : pl) {
                            if (!(o instanceof String ps)) throw new IllegalArgumentException(where + "waTemplateParams must be strings");
                            p.add(ps);
                        }
                    } else if (params instanceof String ps) {
                        p = SequenceEngine.parseParams(objectMapper, ps);
                    } else if (params == null) {
                        p = List.of();
                    } else {
                        throw new IllegalArgumentException(where + "waTemplateParams must be an array of strings");
                    }
                    if (p.size() > MAX_TEMPLATE_PARAMS)
                        throw new IllegalArgumentException(where + "at most " + MAX_TEMPLATE_PARAMS + " template parameters");
                    for (String ps : p) {
                        if (ps.length() > 1000) throw new IllegalArgumentException(where + "template parameters must be at most 1000 characters");
                    }
                    try {
                        st.setWaTemplateParams(p.isEmpty() ? null : objectMapper.writeValueAsString(p));
                    } catch (Exception e) {
                        throw new IllegalArgumentException(where + "waTemplateParams are invalid");
                    }
                }
                default -> { }
            }
            if (!SequenceStep.WHATSAPP.equals(channel)) {
                st.setSubject(subject);
                st.setBody(body);
            }
            out.add(st);
        }
        return out;
    }

    private List<SequenceStep> saveSteps(Long sequenceId, List<SequenceStep> steps) {
        List<SequenceStep> saved = new ArrayList<>();
        for (SequenceStep st : steps) {
            st.setSequenceId(sequenceId);
            saved.add(stepRepo.save(st));
        }
        stepRepo.flush();
        return saved;
    }

    // ── Access helpers ──────────────────────────────────────────────────────

    private record Target(String name, String email, String phone, Long ownerId) {}

    private Target requireTarget(User actor, String type, Long id, Action action) {
        Long orgId = actor.getOrganizationId();
        if ("Lead".equals(type)) {
            Lead l = leadRepo.findByIdAndOrganizationId(id, orgId)
                    .orElseThrow(() -> new NoSuchElementException("Lead not found"));
            permissionService.require(actor, action, ResourceType.LEAD, l.getOrganizationId(), l.getOwnerId());
            return new Target(l.getName(), l.getEmail(), l.getPhone(), l.getOwnerId());
        }
        Contact c = contactRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Contact not found"));
        permissionService.require(actor, action, ResourceType.CONTACT, c.getOrganizationId(), c.getOwnerId());
        return new Target(c.getName(), c.getEmail(), c.getPhone(), c.getOwnerId());
    }

    /** Pause / resume / unenroll: Manager+ on sequences, or EDIT on the record (its current owner). */
    private void requireManage(User actor, SequenceEnrollment e) {
        if (permissionService.can(actor, Action.EDIT, ResourceType.SEQUENCE, e.getOrganizationId(), null)) return;
        Long owner = e.getOwnerUserId();
        if ("Lead".equals(e.getRelatedType())) {
            owner = leadRepo.findByIdAndOrganizationId(e.getRelatedId(), e.getOrganizationId()).map(Lead::getOwnerId).orElse(owner);
        } else {
            owner = contactRepo.findByIdAndOrganizationId(e.getRelatedId(), e.getOrganizationId()).map(Contact::getOwnerId).orElse(owner);
        }
        permissionService.require(actor, Action.EDIT, resourceOf(e.getRelatedType()), e.getOrganizationId(), owner);
    }

    private Sequence requireSequence(User actor, Long id) {
        return sequenceRepo.findByIdAndOrganizationId(id, actor.getOrganizationId())
                .orElseThrow(() -> new NoSuchElementException("Sequence not found"));
    }

    private static ResourceType resourceOf(String relatedType) {
        return "Lead".equals(relatedType) ? ResourceType.LEAD : ResourceType.CONTACT;
    }

    static String relatedType(Object raw) {
        String s = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "lead", "leads" -> "Lead";
            case "contact", "contacts" -> "Contact";
            default -> throw new IllegalArgumentException("relatedType must be Lead or Contact");
        };
    }

    // ── Views ───────────────────────────────────────────────────────────────

    private Map<String, Long> countsFor(Sequence s) {
        Map<String, Long> m = new TreeMap<>();
        for (Object[] row : enrollmentRepo.countBySequenceAndStatus(s.getOrganizationId())) {
            if (s.getId().equals(row[0])) m.put((String) row[1], ((Number) row[2]).longValue());
        }
        return m;
    }

    private Map<String, Object> view(Sequence s, List<SequenceStep> steps, Map<String, Long> counts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("active", s.isActive());
        m.put("emailAccountId", s.getEmailAccountId());
        m.put("whatsappAccountId", s.getWhatsappAccountId());
        m.put("createdBy", s.getCreatedBy());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        m.put("version", s.getVersion());
        List<Map<String, Object>> st = new ArrayList<>();
        for (SequenceStep x : steps) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", x.getId());
            sm.put("stepIndex", x.getStepIndex());
            sm.put("channel", x.getChannel());
            sm.put("delayDays", x.getDelayDays());
            sm.put("delayHours", x.getDelayHours());
            sm.put("subject", x.getSubject());
            sm.put("body", x.getBody());
            sm.put("waTemplateName", x.getWaTemplateName());
            sm.put("waTemplateLanguage", x.getWaTemplateLanguage());
            sm.put("waTemplateParams", SequenceEngine.parseParams(objectMapper, x.getWaTemplateParams()));
            st.add(sm);
        }
        m.put("steps", st);
        Map<String, Long> c = counts == null ? new TreeMap<>() : counts;
        m.put("enrollmentCounts", c);
        m.put("activeEnrollments", c.getOrDefault(SequenceEnrollment.ACTIVE, 0L) + c.getOrDefault(SequenceEnrollment.PAUSED, 0L));
        return m;
    }

    private Map<String, Object> enrollmentView(SequenceEnrollment e, String sequenceName, String recordName, int stepCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("sequenceId", e.getSequenceId());
        m.put("sequenceName", sequenceName);
        m.put("relatedType", e.getRelatedType());
        m.put("relatedId", e.getRelatedId());
        m.put("recordName", recordName);
        m.put("ownerUserId", e.getOwnerUserId());
        m.put("enrolledBy", e.getEnrolledBy());
        m.put("status", e.getStatus());
        m.put("currentStep", e.getCurrentStep());
        m.put("stepCount", stepCount);
        m.put("nextRunAt", e.getNextRunAt());
        m.put("skippedChannels", new ArrayList<>(e.skippedChannelSet()));
        m.put("stopReason", e.getStopReason());
        m.put("respondedAt", e.getRespondedAt());
        m.put("completedAt", e.getCompletedAt());
        m.put("createdAt", e.getCreatedAt());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }

    private String sequenceName(Long id) {
        return sequenceRepo.findById(id).map(Sequence::getName).orElse(null);
    }

    private int stepCount(Long sequenceId) {
        return (int) stepRepo.countBySequenceId(sequenceId);
    }

    private String recordName(SequenceEnrollment e) {
        SequenceEngine.TargetRecord r = engine.loadRecord(e);
        return r == null ? null : r.name();
    }

    private Map<String, String> recordNames(List<SequenceEnrollment> rows) {
        Map<String, String> names = new HashMap<>();
        List<Long> leadIds = rows.stream().filter(e -> "Lead".equals(e.getRelatedType())).map(SequenceEnrollment::getRelatedId).distinct().toList();
        List<Long> contactIds = rows.stream().filter(e -> "Contact".equals(e.getRelatedType())).map(SequenceEnrollment::getRelatedId).distinct().toList();
        if (!leadIds.isEmpty()) leadRepo.findAllById(leadIds).forEach(l -> names.put("Lead:" + l.getId(), l.getName()));
        if (!contactIds.isEmpty()) contactRepo.findAllById(contactIds).forEach(c -> names.put("Contact:" + c.getId(), c.getName()));
        return names;
    }

    // ── Small parsers ───────────────────────────────────────────────────────

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static int intIn(Object o, int min, int max, String field) {
        if (o == null) return 0;
        long v;
        if (o instanceof Number n) v = n.longValue();
        else {
            try { v = Long.parseLong(String.valueOf(o).trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException(field + " must be a whole number"); }
        }
        if (v < min || v > max) throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        return (int) v;
    }

    static Long longValue(Object o, String field) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException(field + " is required");
    }
}
