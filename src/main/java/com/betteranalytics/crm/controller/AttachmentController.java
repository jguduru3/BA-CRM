package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * File/image attachments — "add files and images" for any CRM or workspace
 * record (Notion-style pages, Lead/Opportunity/Contact/Company detail,
 * whiteboard items...). Bytes go through FileStorageService (local disk by
 * default, Azure Blob when configured); this controller only ever sees a
 * MultipartFile in and an InputStream out, so it works identically either way.
 *
 * GET    /api/attachments?relatedType=X&relatedId=Y  — list attachments for a record
 * POST   /api/attachments                            — upload (multipart: file, relatedType, relatedId)
 * GET    /api/attachments/{id}/download               — stream the file back
 * DELETE /api/attachments/{id}                        — remove
 *
 * Access to an attachment is always derived from its parent record: listing
 * and downloading require VIEW on the parent, uploading and deleting require
 * EDIT on it. relatedType is restricted to the fixed set below.
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);

    private static final long MAX_SIZE_BYTES = 25L * 1024 * 1024; // 25MB — matches application.yml multipart limit
    private static final int  MAX_LIST = 500;

    /** Every relatedType the UI attaches files to (AttachmentList / Whiteboard upload) plus the other CRM objects. */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "Lead", "Opportunity", "Contact", "Company", "Task",
            "Document", "DatabaseRecord", "Note", "Meeting", "Whiteboard", "Project");

    private final AttachmentRepository attachmentRepository;
    private final FileStorageService storageService;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final TaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final DatabaseRecordRepository databaseRecordRepository;
    private final NoteRepository noteRepository;
    private final MeetingRepository meetingRepository;
    private final WhiteboardBoardRepository whiteboardBoardRepository;
    private final WhiteboardItemRepository whiteboardItemRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public AttachmentController(AttachmentRepository attachmentRepository,
                                 FileStorageService storageService,
                                 CurrentUser currentUser,
                                 AuditService auditService,
                                 PermissionService permissionService,
                                 LeadRepository leadRepository,
                                 OpportunityRepository opportunityRepository,
                                 ContactRepository contactRepository,
                                 CompanyRepository companyRepository,
                                 TaskRepository taskRepository,
                                 DocumentRepository documentRepository,
                                 DatabaseRecordRepository databaseRecordRepository,
                                 NoteRepository noteRepository,
                                 MeetingRepository meetingRepository,
                                 WhiteboardBoardRepository whiteboardBoardRepository,
                                 WhiteboardItemRepository whiteboardItemRepository,
                                 ProjectRepository projectRepository,
                                 ProjectMemberRepository projectMemberRepository) {
        this.attachmentRepository = attachmentRepository;
        this.storageService = storageService;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.permissionService = permissionService;
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.taskRepository = taskRepository;
        this.documentRepository = documentRepository;
        this.databaseRecordRepository = databaseRecordRepository;
        this.noteRepository = noteRepository;
        this.meetingRepository = meetingRepository;
        this.whiteboardBoardRepository = whiteboardBoardRepository;
        this.whiteboardItemRepository = whiteboardItemRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String relatedType, @RequestParam Long relatedId,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        if (!ALLOWED_TYPES.contains(relatedType)) return badRequest("unsupported relatedType");
        requireParentAccess(actor, relatedType, relatedId, false);
        return ResponseEntity.ok(attachmentRepository
                .findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
                        actor.getOrganizationId(), relatedType, relatedId,
                        com.betteranalytics.crm.service.Paging.of(page, size, limit, 200, MAX_LIST, null)));
    }

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam String relatedType,
                                     @RequestParam Long relatedId) {
        User actor = currentUser.get();
        if (!ALLOWED_TYPES.contains(relatedType)) return badRequest("unsupported relatedType");
        if (file == null || file.isEmpty()) return badRequest("file is empty");
        if (file.getSize() > MAX_SIZE_BYTES) {
            return ResponseEntity.status(413).body(Map.of("error", "file exceeds the 25MB limit"));
        }
        requireParentAccess(actor, relatedType, relatedId, true);

        String displayName = displayFileName(file.getOriginalFilename());
        String contentType;
        try (InputStream in = file.getInputStream()) {
            contentType = FileStorageService.sniffContentType(in.readNBytes(16));
        } catch (IOException e) {
            log.warn("Attachment upload: could not read uploaded file ({})", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(Map.of("error", "upload failed"));
        }

        String key = storageService.buildKey(actor.getOrganizationId(), relatedType, relatedId, displayName);
        FileStorageService.StoredFile stored;
        try (InputStream in = file.getInputStream()) {
            stored = storageService.store(key, in, file.getSize(), contentType);
        } catch (Exception e) {
            log.warn("Attachment upload: storage write failed ({})", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(Map.of("error", "upload failed"));
        }
        if (stored.sizeBytes() > MAX_SIZE_BYTES) {
            storageService.delete(stored.backend(), stored.storageKey());
            return ResponseEntity.status(413).body(Map.of("error", "file exceeds the 25MB limit"));
        }

        Attachment att = new Attachment();
        att.setOrganizationId(actor.getOrganizationId());
        att.setRelatedType(relatedType);
        att.setRelatedId(relatedId);
        att.setFileName(displayName);
        att.setContentType(contentType);
        att.setSizeBytes(stored.sizeBytes());
        att.setStorageBackend(stored.backend());
        att.setStorageKey(stored.storageKey());
        att.setUploadedBy(actor.getId());
        att.setCreatedAt(Instant.now());
        try {
            attachmentRepository.save(att);
        } catch (RuntimeException e) {
            // Don't leave an orphaned blob behind when the metadata row can't be written.
            storageService.delete(stored.backend(), stored.storageKey());
            log.warn("Attachment upload: metadata save failed ({})", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(Map.of("error", "upload failed"));
        }

        auditService.log("create", "attachments", att.getId(), att.getFileName(), null,
                Map.of("relatedType", relatedType, "relatedId", relatedId, "sizeBytes", att.getSizeBytes()));
        return ResponseEntity.status(201).body(att);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable Long id) {
        User actor = currentUser.get();
        Attachment att = attachmentRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (att == null) return ResponseEntity.status(404).body(Map.of("error", "attachment not found"));
        requireParentAccess(actor, att.getRelatedType(), att.getRelatedId(), false);

        String ct = att.getContentType() == null ? "" : att.getContentType().toLowerCase(Locale.ROOT);
        boolean inline = FileStorageService.SAFE_INLINE_TYPES.contains(ct);
        MediaType mediaType = inline ? MediaType.parseMediaType(ct) : MediaType.APPLICATION_OCTET_STREAM;

        InputStream data;
        try {
            data = storageService.load(att.getStorageBackend(), att.getStorageKey());
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", "file not found in storage"));
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(inline, att.getFileName()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .body(new InputStreamResource(data));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        Attachment att = attachmentRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (att == null) return ResponseEntity.status(404).body(Map.of("error", "attachment not found"));
        try {
            requireParentAccess(actor, att.getRelatedType(), att.getRelatedId(), true);
        } catch (NoSuchElementException parentGone) {
            // Orphaned attachment (parent deleted): only the uploader or a manager+ may clean it up.
            if (!actor.getId().equals(att.getUploadedBy()) && !isManagerPlus(actor)) {
                throw new AccessDeniedException("not permitted to delete this attachment");
            }
        }

        // whiteboard_items.attachment_id has no ON DELETE action — detach first so the delete can't 500.
        whiteboardItemRepository.clearAttachmentReferences(actor.getOrganizationId(), att.getId());
        attachmentRepository.delete(att);
        storageService.delete(att.getStorageBackend(), att.getStorageKey());
        auditService.log("delete", "attachments", id, att.getFileName(), att, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the parent record inside the caller's org and enforces VIEW
     * (write=false) or EDIT (write=true) on it.
     * Throws NoSuchElementException (404) when the parent doesn't exist in the org,
     * AccessDeniedException (403) when the caller may not access it.
     */
    private void requireParentAccess(User actor, String relatedType, Long relatedId, boolean write) {
        Long orgId = actor.getOrganizationId();
        if (relatedType == null || relatedId == null || !ALLOWED_TYPES.contains(relatedType)) {
            throw new NoSuchElementException("related record not found");
        }
        Action action = write ? Action.EDIT : Action.VIEW;
        switch (relatedType) {
            case "Lead" -> {
                Lead l = leadRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                permissionService.require(actor, action, ResourceType.LEAD, l.getOrganizationId(), l.getOwnerId());
            }
            case "Opportunity" -> {
                Opportunity o = opportunityRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                boolean ok = permissionService.can(actor, action, ResourceType.OPPORTUNITY, o.getOrganizationId(), o.getOwnerId())
                        || (!write && permissionService.canViewSourcedOpportunity(actor, o));
                if (!ok) throw new AccessDeniedException("not permitted to access this opportunity's files");
            }
            case "Contact" -> {
                Contact c = contactRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                permissionService.require(actor, action, ResourceType.CONTACT, c.getOrganizationId(), c.getOwnerId());
            }
            case "Company" -> {
                Company c = companyRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                permissionService.require(actor, action, ResourceType.COMPANY, c.getOrganizationId(), c.getOwnerId());
            }
            case "Task" -> {
                Task t = taskRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                permissionService.require(actor, action, ResourceType.TASK, t.getOrganizationId(), t.getAssigneeId());
            }
            case "Document" -> {
                Document d = documentRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                permissionService.require(actor, action, ResourceType.DOCUMENT, d.getOrganizationId(), d.getOwnerId());
            }
            case "DatabaseRecord" -> {
                DatabaseRecord r = databaseRecordRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                permissionService.require(actor, action, ResourceType.DATABASE, r.getOrganizationId(), r.getOwnerId());
            }
            case "Note" -> {
                Note n = noteRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                permissionService.require(actor, action, ResourceType.NOTE, n.getOrganizationId(), n.getCreatedBy());
            }
            case "Meeting" -> {
                Meeting m = meetingRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                permissionService.require(actor, action, ResourceType.MEETING, m.getOrganizationId(), m.getOrganizerId());
            }
            case "Whiteboard" -> {
                WhiteboardBoard b = whiteboardBoardRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                permissionService.require(actor, action, ResourceType.WHITEBOARD, b.getOrganizationId(), b.getCreatedBy());
            }
            case "Project" -> {
                Project p = projectRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::parentNotFound);
                // Uploading to a project is contributing to it: owner scope, or project membership
                // (non-viewer) layered on top for roles that have the PROJECT edit feature at all.
                if (!permissionService.can(actor, action, ResourceType.PROJECT, p.getOrganizationId(), p.getOwnerId())) {
                    boolean member = write
                            && permissionService.can(actor, Action.EDIT, ResourceType.PROJECT, orgId, actor.getId())
                            && projectMemberRepository.findByProjectIdAndUserId(p.getId(), actor.getId())
                                    .map(m -> !"viewer".equals(m.getRole())).orElse(false);
                    if (!member) permissionService.require(actor, action, ResourceType.PROJECT, p.getOrganizationId(), p.getOwnerId());
                }
            }
            default -> throw parentNotFound();
        }
    }

    private NoSuchElementException parentNotFound() {
        return new NoSuchElementException("related record not found");
    }

    private static boolean isManagerPlus(User u) {
        return u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.VP_SALES || u.getRole() == Role.MANAGER;
    }

    private static ResponseEntity<Map<String, String>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** Display name: last path segment, no control characters, at most 255 chars. */
    private static String displayFileName(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty()) name = "file";
        if (name.length() > 255) name = name.substring(name.length() - 255);
        return name;
    }

    /** Content-Disposition with an ASCII fallback plus an RFC 5987 encoded UTF-8 filename*. */
    private static String contentDisposition(boolean inline, String fileName) {
        String name = fileName == null || fileName.isBlank() ? "file" : fileName;
        StringBuilder ascii = new StringBuilder();
        for (char c : name.toCharArray()) {
            ascii.append(c >= 0x20 && c < 0x7F && c != '"' && c != '\\' ? c : '_');
        }
        return (inline ? "inline" : "attachment")
                + "; filename=\"" + ascii + "\""
                + "; filename*=UTF-8''" + rfc5987Encode(name);
    }

    private static String rfc5987Encode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean attrChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0;
            if (attrChar) sb.append((char) c);
            else sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
        }
        return sb.toString();
    }
}
