package com.betteranalytics.crm.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** One freeform element on a whiteboard: a sticky note, text box, shape,
 * image, or connector. Position/size are free-floating doubles (canvas
 * coordinates), not a grid — matches a Jamboard/Miro-style surface. */
@Entity
@Table(name = "whiteboard_items")
@Getter @Setter
public class WhiteboardItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "board_id", nullable = false)
    private Long boardId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    // sticky | text | shape | image | connector
    @Column(nullable = false, length = 20)
    private String type = "sticky";

    private double x = 0;
    private double y = 0;
    private double w = 200;
    private double h = 140;
    private double rotation = 0;

    // Jackson's legacy bean-naming mangles a getZIndex()-style accessor's
    // JSON key to "zindex" (verified against the actual serializer — see
    // Contact/TodoItem's isXxx fields for the same class of surprise) —
    // pinned explicitly so the wire format stays proper camelCase.
    @JsonProperty("zIndex")
    @Column(name = "z_index")
    private int zIndex = 0;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String color = "#ffe066";

    @Column(name = "attachment_id")
    private Long attachmentId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
