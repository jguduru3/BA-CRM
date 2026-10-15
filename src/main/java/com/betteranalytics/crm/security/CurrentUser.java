package com.betteranalytics.crm.security;

import com.betteranalytics.crm.entity.User;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Central place that resolves "who is making this request" and, critically,
 * "which organization are they scoped to" — every repository call that reads
 * or writes tenant data should go through here instead of trusting a client-
 * supplied organizationId. This is what was missing in the Node app (where
 * organizationId was hardcoded to 1 in several routes).
 */
@Component
public class CurrentUser {

    public User get() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof User user) return user;
        throw new IllegalStateException("No authenticated user in context");
    }

    public Long organizationId() {
        return get().getOrganizationId();
    }

    public Long userId() {
        return get().getId();
    }
}
