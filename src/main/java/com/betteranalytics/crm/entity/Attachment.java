package com.betteranalytics.crm.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * A file or image uploaded against any CRM/workspace record (Lead,
 * Opportunity, Contact, Company, Document, Note, DatabaseRecord, Whiteboard
 * item...). Bytes live in whichever backend FileStorageService is configured
 * for (local disk by default, Azure Blob Storage when
 * AZURE_STORAGE_CONNECTION_STRING is set) — storageKey is the backend-
 * relative path, storageBackend records which one wrote it so downloads
 * still resolve correctly after a backend switch.
 */
@Entity
@Table(name = "attachments")
@Getter @Setter
public class Attachment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "related_type", nullable = false, length = 30)
    private String relatedType;

    @Column(name = "related_id", nullable = false)
    private Long relatedId;

    @Column(name = "file_name", nullable = false, length = 400)
    private String fileName;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_backend", nullable = false, length = 20)
    private String storageBackend = "local";

    // Internal backend path — never exposed to clients.
    @JsonIgnore
    @Column(name = "storage_key", nullable = false, length = 600)
    private String storageKey;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
