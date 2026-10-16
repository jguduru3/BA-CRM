package com.betteranalytics.crm.sequences;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * One step of a sequence. The delay is measured from the previous step (or
 * from enrollment for the first step). EMAIL uses subject/body, TASK uses
 * subject as the task title, WHATSAPP sends an approved template whose body
 * parameters are a JSON array of strings; all text supports {{lead.name}}-style
 * variables (see {@link SequenceTemplates}).
 */
@Entity
@Table(name = "sequence_steps")
@Getter
@Setter
public class SequenceStep {

    public static final String EMAIL = "EMAIL";
    public static final String WHATSAPP = "WHATSAPP";
    public static final String TASK = "TASK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sequence_id", nullable = false)
    private Long sequenceId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(name = "delay_days", nullable = false)
    private int delayDays;

    @Column(name = "delay_hours", nullable = false)
    private int delayHours;

    @Column(length = 300)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "wa_template_name", length = 100)
    private String waTemplateName;

    @Column(name = "wa_template_language", length = 10)
    private String waTemplateLanguage;

    @Column(name = "wa_template_params", columnDefinition = "TEXT")
    private String waTemplateParams;

    public Duration delay() {
        return Duration.ofDays(delayDays).plusHours(delayHours);
    }
}
