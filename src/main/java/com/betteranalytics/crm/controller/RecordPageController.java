package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Record pages — one workspace page per CRM record.
 * GET /api/pages/{type}/{id}   — get the page for a record (an unsaved, empty page if none exists yet)
 * PUT /api/pages/{type}/{id}   — update page metadata (icon, cover) — creates the page on first write
 * GET /api/pages/{type}/{id}/blocks
 * POST /api/pages/{type}/{id}/blocks
 * PUT /api/pages/{type}/{id}/blocks/{blockId}
 * DELETE /api/pages/{type}/{id}/blocks/{blockId}
 *
 * Access follows the parent record: reading needs VIEW on it, writing needs
 * EDIT on it (plus the RECORD_PAGE feature permission). GETs never insert rows.
 */
@RestController
@RequestMapping("/api/pages")
public class RecordPageController {

    private static final Set<String> RELATED_TYPES = Set.of(
            "Lead", "Opportunity", "Contact", "Company", "Task", "DatabaseRecord", "CustomRecord");
    private static final Set<String> BLOCK_TYPES = Set.of(
            "text", "heading", "checklist", "divider", "embed", "property_table", "ai_summary");

    private final RecordPageRepository pageRepository;
    private final PageBlockRepository blockRepository;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final TaskRepository taskRepository;
    private final DatabaseRecordRepository databaseRecordRepository;

    public RecordPageController(RecordPageRepository pageRepository, PageBlockRepository blockRepository,
                                 CurrentUser currentUser, PermissionService permissionService,
                                 AuditService auditService, ObjectMapper objectMapper,
                                 LeadRepository leadRepository, OpportunityRepository opportunityRepository,
                                 ContactRepository contactRepository, CompanyRepository companyRepository,
                                 TaskRepository taskRepository, DatabaseRecordRepository databaseRecordRepository) {
        this.pageRepository = pageRepository;
        this.blockRepository = blockRepository;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.taskRepository = taskRepository;
        this.databaseRecordRepository = databaseRecordRepository;
    }

    // ── Page ─────────────────────────────────────────────────────────────────

    @GetMapping("/{relatedType}/{relatedId}")
    public ResponseEntity<Map<String, Object>> getPage(@PathVariable String relatedType,
                                                        @PathVariable Long relatedId) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, false);
        RecordPage page = findPage(relatedType, relatedId).orElse(null);
        if (page == null) {
            // Virtual page: nothing is persisted until the first write.
            RecordPage virtual = new RecordPage();
            virtual.setOrganizationId(actor.getOrganizationId());
            virtual.setRelatedType(relatedType);
            virtual.setRelatedId(relatedId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("page", virtual);
            result.put("blocks", List.of());
            return ResponseEntity.ok(result);
        }
        List<PageBlock> blocks = blockRepository.findByPageIdOrderByPosition(page.getId());
        return ResponseEntity.ok(Map.of("page", page, "blocks", blocks));
    }

    @PutMapping("/{relatedType}/{relatedId}")
    public ResponseEntity<RecordPage> updatePage(@PathVariable String relatedType,
                                                  @PathVariable Long relatedId,
                                                  @RequestBody RecordPage body) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, true);
        RecordPage page = getOrCreate(actor, relatedType, relatedId);
        if (body.getIcon() != null) page.setIcon(body.getIcon());
        if (body.getCoverColor() != null) page.setCoverColor(body.getCoverColor());
        page.setUpdatedAt(Instant.now());
        pageRepository.save(page);
        return ResponseEntity.ok(page);
    }

    // ── Blocks ────────────────────────────────────────────────────────────────

    @GetMapping("/{relatedType}/{relatedId}/blocks")
    public List<PageBlock> listBlocks(@PathVariable String relatedType, @PathVariable Long relatedId) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, false);
        return findPage(relatedType, relatedId)
                .map(p -> blockRepository.findByPageIdOrderByPosition(p.getId()))
                .orElse(List.of());
    }

    @PostMapping("/{relatedType}/{relatedId}/blocks")
    public ResponseEntity<?> addBlock(@PathVariable String relatedType,
                                      @PathVariable Long relatedId,
                                      @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, true);
        PageBlock body = bind(raw);
        if (body.getBlockType() != null && !BLOCK_TYPES.contains(body.getBlockType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported blockType"));
        }
        RecordPage page = getOrCreate(actor, relatedType, relatedId);
        PageBlock block = new PageBlock();
        block.setPageId(page.getId());
        block.setBlockType(body.getBlockType() != null ? body.getBlockType() : "text");
        block.setContent(body.getContent());
        block.setPosition(body.getPosition());
        block.setChecked(body.isChecked());
        block.setCreatedAt(Instant.now());
        block.setUpdatedAt(Instant.now());
        blockRepository.save(block);
        page.setUpdatedAt(Instant.now());
        pageRepository.save(page);
        return ResponseEntity.status(201).body(block);
    }

    @PutMapping("/{relatedType}/{relatedId}/blocks/{blockId}")
    public ResponseEntity<?> updateBlock(@PathVariable String relatedType,
                                         @PathVariable Long relatedId,
                                         @PathVariable Long blockId,
                                         @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, true);
        RecordPage page = findPage(relatedType, relatedId).orElse(null);
        if (page == null) return ResponseEntity.notFound().build();
        PageBlock existing = blockRepository.findById(blockId).orElse(null);
        if (existing == null || !Objects.equals(existing.getPageId(), page.getId()))
            return ResponseEntity.notFound().build();
        PageBlock body = bind(raw);
        if (body.getBlockType() != null && !BLOCK_TYPES.contains(body.getBlockType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported blockType"));
        }
        if (raw.containsKey("content")) existing.setContent(body.getContent());
        if (body.getBlockType() != null) existing.setBlockType(body.getBlockType());
        if (raw.containsKey("position")) existing.setPosition(body.getPosition());
        if (raw.containsKey("checked")) existing.setChecked(body.isChecked());
        existing.setUpdatedAt(Instant.now());
        blockRepository.save(existing);
        page.setUpdatedAt(Instant.now());
        pageRepository.save(page);
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{relatedType}/{relatedId}/blocks/{blockId}")
    public ResponseEntity<?> deleteBlock(@PathVariable String relatedType,
                                          @PathVariable Long relatedId,
                                          @PathVariable Long blockId) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, true);
        RecordPage page = findPage(relatedType, relatedId).orElse(null);
        if (page == null) return ResponseEntity.notFound().build();
        PageBlock existing = blockRepository.findById(blockId).orElse(null);
        if (existing == null || !Objects.equals(existing.getPageId(), page.getId()))
            return ResponseEntity.notFound().build();
        blockRepository.delete(existing);
        page.setUpdatedAt(Instant.now());
        pageRepository.save(page);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Optional<RecordPage> findPage(String relatedType, Long relatedId) {
        return pageRepository.findByOrganizationIdAndRelatedTypeAndRelatedId(
                currentUser.organizationId(), relatedType, relatedId);
    }

    /**
     * Race-safe get-or-create: record_pages has UNIQUE (organization_id,
     * related_type, related_id), so if a concurrent request inserts first our
     * insert fails and we just re-read the winner's row.
     */
    private RecordPage getOrCreate(User actor, String relatedType, Long relatedId) {
        Optional<RecordPage> existing = findPage(relatedType, relatedId);
        if (existing.isPresent()) return existing.get();
        permissionService.require(actor, Action.CREATE, ResourceType.RECORD_PAGE, actor.getOrganizationId(), null);
        RecordPage p = new RecordPage();
        p.setOrganizationId(actor.getOrganizationId());
        p.setRelatedType(relatedType);
        p.setRelatedId(relatedId);
        p.setCreatedBy(actor.getId());
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        try {
            return pageRepository.save(p);
        } catch (DataIntegrityViolationException raced) {
            return findPage(relatedType, relatedId).orElseThrow(() -> raced);
        }
    }

    /**
     * Validates the parent record exists in the caller's org and enforces
     * VIEW (write=false) / EDIT (write=true) on both the parent and RECORD_PAGE.
     */
    private void requireAccess(User actor, String relatedType, Long relatedId, boolean write) {
        if (relatedType == null || relatedId == null || !RELATED_TYPES.contains(relatedType)) {
            throw new IllegalArgumentException("unsupported record type");
        }
        Long orgId = actor.getOrganizationId();
        Action action = write ? Action.EDIT : Action.VIEW;
        permissionService.require(actor, action, ResourceType.RECORD_PAGE, orgId, null);
        switch (relatedType) {
            case "Lead" -> {
                Lead l = leadRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.LEAD, l.getOrganizationId(), l.getOwnerId());
            }
            case "Opportunity" -> {
                Opportunity o = opportunityRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                boolean ok = permissionService.can(actor, action, ResourceType.OPPORTUNITY, o.getOrganizationId(), o.getOwnerId())
                        || (!write && permissionService.canViewSourcedOpportunity(actor, o));
                if (!ok) throw new AccessDeniedException("not permitted to access this opportunity's page");
            }
            case "Contact" -> {
                Contact c = contactRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.CONTACT, c.getOrganizationId(), c.getOwnerId());
            }
            case "Company" -> {
                Company c = companyRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.COMPANY, c.getOrganizationId(), c.getOwnerId());
            }
            case "Task" -> {
                Task t = taskRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.TASK, t.getOrganizationId(), t.getAssigneeId());
            }
            case "DatabaseRecord", "CustomRecord" -> {
                DatabaseRecord r = databaseRecordRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.DATABASE, r.getOrganizationId(), r.getOwnerId());
            }
            default -> throw notFound();
        }
    }

    private NoSuchElementException notFound() {
        return new NoSuchElementException("record not found");
    }

    /** Typed view of a JSON body; type mismatches become IllegalArgumentException (400). */
    private PageBlock bind(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("request body is required");
        try {
            return objectMapper.convertValue(raw, PageBlock.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid request body");
        }
    }
}
