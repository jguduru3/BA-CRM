package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "property_values")
@Getter @Setter
public class PropertyValue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "text_value", columnDefinition = "TEXT")
    private String textValue;

    @Column(name = "number_value", precision = 20, scale = 4)
    private BigDecimal numberValue;

    @Column(name = "date_value")
    private Instant dateValue;

    @Column(name = "bool_value")
    private Boolean boolValue;

    // JSON for multi-value types (multi_select option ids, relation record ids, file info)
    @Column(name = "json_value", columnDefinition = "TEXT")
    private String jsonValue;
}
