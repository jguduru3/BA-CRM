package com.betteranalytics.crm.dto;

public class UserUpdateRequest {
    public String name;
    public String role;
    public String initials;
    public String color;
    public Boolean active; // Boolean (not boolean) so "not sent" is distinguishable from "sent false"
    public Long reportsToId;    // NEW — who this user rolls up to (see User.reportsToId).
                                 // Only ever SETS a value when provided; to clear it back to
                                 // null (e.g. promoting someone to VP_SALES with nobody above
                                 // them), use POST /{id}/clear-reports-to instead — a magic
                                 // sentinel value here would be worse than a dedicated action.
    public String territory;    // NEW — free-text territory tag, meaningful for FIELD_SALES
}
