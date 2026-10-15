package com.betteranalytics.crm.permission;

import com.betteranalytics.crm.entity.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests of the feature matrix — no Spring context. */
class FeaturePermissionTableTest {

    private static final Set<Action> WRITES =
            EnumSet.of(Action.CREATE, Action.EDIT, Action.DELETE, Action.ASSIGN, Action.RESTORE);

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void supportNeverGetsAWriteOnAnyResource(ResourceType resource) {
        for (Action a : WRITES) {
            Scope s = FeaturePermissionTable.lookup(Role.SUPPORT, a, resource);
            if (resource == ResourceType.NOTIFICATION && a == Action.EDIT) {
                assertThat(s).as("SUPPORT marks its own notifications read").isEqualTo(Scope.OWN);
            } else {
                assertThat(s).as("SUPPORT %s %s", a, resource).isEqualTo(Scope.NONE);
            }
        }
    }

    @Test
    void teamRostersViewManagerUpChangeVpOrSuperAdmin() {
        for (Role r : Role.values()) {
            boolean managerUp = r == Role.SUPER_ADMIN || r == Role.VP_SALES || r == Role.MANAGER;
            boolean senior = r == Role.SUPER_ADMIN || r == Role.VP_SALES;
            assertThat(FeaturePermissionTable.lookup(r, Action.VIEW, ResourceType.TEAM) != Scope.NONE)
                    .as("%s view teams", r).isEqualTo(managerUp);
            for (Action a : EnumSet.of(Action.CREATE, Action.EDIT, Action.DELETE)) {
                assertThat(FeaturePermissionTable.lookup(r, a, ResourceType.TEAM) != Scope.NONE)
                        .as("%s %s teams", r, a).isEqualTo(senior);
            }
        }
    }

    @Test
    void departmentsAndOrgSettingsAreChangedBySuperAdminOnly() {
        for (Role r : Role.values()) {
            boolean admin = r == Role.SUPER_ADMIN;
            for (Action a : EnumSet.of(Action.CREATE, Action.EDIT, Action.DELETE)) {
                assertThat(FeaturePermissionTable.lookup(r, a, ResourceType.DEPARTMENT) != Scope.NONE)
                        .as("%s %s departments", r, a).isEqualTo(admin);
            }
            assertThat(FeaturePermissionTable.lookup(r, Action.EDIT, ResourceType.ORG_SETTINGS) != Scope.NONE)
                    .as("%s edit org settings", r).isEqualTo(admin);
            assertThat(FeaturePermissionTable.lookup(r, Action.VIEW, ResourceType.ORG_SETTINGS))
                    .as("everyone reads the org profile").isEqualTo(Scope.ORG);
        }
    }

    @Test
    void auditLogIsOrgForSeniorTeamForManagersNoneOtherwise() {
        assertThat(FeaturePermissionTable.lookup(Role.SUPER_ADMIN, Action.VIEW, ResourceType.AUDIT_LOG)).isEqualTo(Scope.ORG);
        assertThat(FeaturePermissionTable.lookup(Role.VP_SALES, Action.VIEW, ResourceType.AUDIT_LOG)).isEqualTo(Scope.ORG);
        assertThat(FeaturePermissionTable.lookup(Role.MANAGER, Action.VIEW, ResourceType.AUDIT_LOG)).isEqualTo(Scope.TEAM);
        for (Role r : EnumSet.of(Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT)) {
            assertThat(FeaturePermissionTable.lookup(r, Action.VIEW, ResourceType.AUDIT_LOG)).isEqualTo(Scope.NONE);
        }
    }

    @Test
    void integrationsAreSuperAdminOnly() {
        for (Role r : Role.values()) {
            for (Action a : Action.values()) {
                Scope expected = r == Role.SUPER_ADMIN ? Scope.ORG : Scope.NONE;
                assertThat(FeaturePermissionTable.lookup(r, a, ResourceType.INTEGRATION)).isEqualTo(expected);
            }
        }
    }
}
