package com.betteranalytics.crm.dto;

import java.time.Instant;

/** Mirrors the fields the React NewLeadModal / edit form send. */
public class LeadRequest {
    public String name;
    public String email;
    public String phone;
    public Long companyId;
    public Long ownerId;
    public String stage;
    public String source;
    public String priority;
    public Long value;
    public String notes;
    public String tags;
    public Instant followUpAt;

    // NEW — data-feed intake fields
    public String address;
    public String businessName;
    public String category;
    public String leadType;

    // NEW — qualification / discovery fields
    public String industry;
    public String geography;
    public String employeeBand;
    public String revenueBand;
    public String requirement;
    public String painPoint;
    public String technologyUsed;
    public String decisionMaker;
    public Integer leadScore;
}
