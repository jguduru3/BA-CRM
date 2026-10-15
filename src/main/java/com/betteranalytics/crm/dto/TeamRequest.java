package com.betteranalytics.crm.dto;

/**
 * Create/update body for /api/teams. A dedicated DTO (instead of binding the
 * Team entity) so clients can't mass-assign id/organizationId, and so "active"
 * is a Boolean: an absent field means "leave unchanged" rather than silently
 * deactivating the team on every partial update.
 */
public class TeamRequest {
    public String name;
    public String description;
    public Long leaderId;
    public Long departmentId;
    public String color;
    public Boolean active;
}
