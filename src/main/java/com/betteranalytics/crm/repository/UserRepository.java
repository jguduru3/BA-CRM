package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    List<User> findByOrganizationId(Long organizationId);
    Optional<User> findByEmail(String email);
    Optional<User> findByIdAndOrganizationId(Long id, Long organizationId);

    // NEW — direct reports of a given user (one level of the sales tree).
    // For PermissionService later: walking a Manager's or VP's full
    // "everyone under me" scope means calling this repeatedly down the tree,
    // not a single query — the tree can be arbitrarily deep in theory (VP ->
    // Manager -> rep is the spec's actual depth, but nothing here assumes
    // exactly two levels).
    List<User> findByReportsToId(Long reportsToId);

    /** Case-insensitive duplicate check — emails are stored lower-cased but legacy rows may not be. */
    boolean existsByEmailIgnoreCase(String email);

    /** Guards against demoting / deactivating the last active Super Admin of a tenant. */
    long countByOrganizationIdAndRoleAndActiveTrue(Long organizationId, Role role);
}
