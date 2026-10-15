package com.betteranalytics.crm.dto;

public class UserCreateRequest {
    public String name;
    public String email;
    public String password;
    public String role;     // SUPER_ADMIN | VP_SALES | MANAGER | EXECUTIVE | INSIDE_SALES | FIELD_SALES | SUPPORT
    public String initials;
    public String color;
    public Long reportsToId;   // NEW — who this user rolls up to (see User.reportsToId)
    public String territory;   // NEW — free-text territory tag, meaningful for FIELD_SALES
}
