package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "page_blocks")
@Getter @Setter
public class PageBlock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "page_id", nullable = false)
    private Long pageId;

    // text | heading | checklist | divider | embed | property_table | ai_summary
    @Column(name = "block_type", nullable = false, length = 30)
    private String blockType = "text";

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private int position = 0;

    @Column(name = "is_checked", nullable = false)
    private boolean checked = false;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
