package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import com.betteranalytics.crm.permission.ResourceType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * "Which rows may this user see" as query predicates (organisation + record
 * scope), shared by reports, search and exports. Each method returns null when
 * the role has no VIEW access to the resource at all.
 */
@Component
public class ScopeSpecs {

    private final PermissionService permissionService;

    public ScopeSpecs(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    private <T> Specification<T> scoped(User actor, ResourceType resource, String ownerAttr) {
        OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, resource);
        if (scope.none()) return null;
        return Specs.<T>org(actor.getOrganizationId()).and(Specs.ownedWithin(scope, ownerAttr));
    }

    public Specification<Lead> leads(User actor) {
        return scoped(actor, ResourceType.LEAD, "ownerId");
    }

    /** Inside Sales also see the deals sourced from their leads (masked to stage only). */
    public Specification<Opportunity> opportunities(User actor) {
        OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, ResourceType.OPPORTUNITY);
        boolean sourced = actor.getRole() == Role.INSIDE_SALES;
        if (scope.none() && !sourced) return null;
        Specification<Opportunity> owned = Specs.ownedWithin(scope, "ownerId");
        if (sourced) owned = owned.or(Specs.eq("sourcedByUserId", actor.getId()));
        return Specs.<Opportunity>org(actor.getOrganizationId()).and(owned);
    }

    public Specification<Company> companies(User actor) {
        return scoped(actor, ResourceType.COMPANY, "ownerId");
    }

    public Specification<Contact> contacts(User actor) {
        return scoped(actor, ResourceType.CONTACT, "ownerId");
    }

    public Specification<Task> tasks(User actor) {
        return scoped(actor, ResourceType.TASK, "assigneeId");
    }

    public Specification<Activity> activities(User actor) {
        return scoped(actor, ResourceType.ACTIVITY, "userId");
    }
}
