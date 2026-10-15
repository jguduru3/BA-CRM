package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.ContactCategory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ContactCategoryRepository extends JpaRepository<ContactCategory, Long> {
    List<ContactCategory> findByOrganizationIdOrderByIsSystemDescNameAsc(Long orgId);
    List<ContactCategory> findByOrganizationIdOrderByIsSystemDescNameAsc(Long orgId, Pageable page);
    Optional<ContactCategory> findByIdAndOrganizationId(Long id, Long orgId);
    boolean existsByOrganizationId(Long orgId);
    long countByOrganizationId(Long orgId);
    boolean existsByOrganizationIdAndName(Long orgId, String name);
    boolean existsByOrganizationIdAndNameAndIdNot(Long orgId, String name, Long id);

    /** Un-categorises every contact of the org that used this category (contacts.category_id has no FK). */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE contacts SET category_id = NULL WHERE organization_id = :orgId AND category_id = :categoryId",
           nativeQuery = true)
    int clearFromContacts(@Param("orgId") Long orgId, @Param("categoryId") Long categoryId);
}
