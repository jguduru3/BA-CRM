package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.ContactCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ContactCategoryRepository extends JpaRepository<ContactCategory, Long> {
    List<ContactCategory> findByOrganizationIdOrderByIsSystemDescNameAsc(Long orgId);
    Optional<ContactCategory> findByIdAndOrganizationId(Long id, Long orgId);
    boolean existsByOrganizationId(Long orgId);
    long countByOrganizationId(Long orgId);
    boolean existsByOrganizationIdAndName(Long orgId, String name);
    boolean existsByOrganizationIdAndNameAndIdNot(Long orgId, String name, Long id);
}
