package com.betteranalytics.crm.dto;

public class PasswordResetRequest {
    public String newPassword;
    // Required when a user changes their OWN password: proves the request comes
    // from someone who knows the current password, not just a stolen session.
    // Ignored when a Super Admin resets someone else's password.
    public String currentPassword;
}
