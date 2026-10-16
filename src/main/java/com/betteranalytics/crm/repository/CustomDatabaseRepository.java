package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.CustomDatabase;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CustomDatabaseRepository extends JpaRepository<CustomDatabase, Long> {
    List<CustomDatabase> findByOrganizationId(Long organizationId);
    List<CustomDatabase> findByOrganizationId(Long organizationId, Pageable pageable);
    Optional<CustomDatabase> findByIdAndOrganizationId(Long id, Long organizationId);
}
