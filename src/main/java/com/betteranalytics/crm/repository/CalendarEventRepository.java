package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long>, JpaSpecificationExecutor<CalendarEvent> {
    List<CalendarEvent> findByOrganizationIdOrderByStartAtAsc(Long orgId);

    @Query("SELECT e FROM CalendarEvent e WHERE e.organizationId = :orgId " +
           "AND e.startAt >= :from AND e.startAt <= :to ORDER BY e.startAt ASC")
    List<CalendarEvent> findByOrganizationIdAndDateRange(
            @Param("orgId") Long orgId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    List<CalendarEvent> findByOrganizationIdAndRelatedTypeAndRelatedId(
            Long orgId, String relatedType, Long relatedId);

    Optional<CalendarEvent> findByIdAndOrganizationId(Long id, Long orgId);
}
