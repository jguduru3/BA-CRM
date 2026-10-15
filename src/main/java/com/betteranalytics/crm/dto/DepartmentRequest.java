package com.betteranalytics.crm.dto;

/**
 * Create/update body for /api/departments. Same reasoning as TeamRequest:
 * no mass-assignable id/organizationId, and Boolean active so partial
 * updates don't reset it.
 */
public class DepartmentRequest {
    public String name;
    public String description;
    public Long headId;
    public Long parentId;
    public Boolean active;
}
