package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.ForecastQuota;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ForecastQuotaRepository extends JpaRepository<ForecastQuota, Long> {

    Optional<ForecastQuota> findByOrganizationIdAndUserIdAndPeriodTypeAndPeriodStart(
            Long organizationId, Long userId, String periodType, LocalDate periodStart);

    List<ForecastQuota> findByOrganizationIdAndPeriodTypeAndPeriodStartIn(
            Long organizationId, String periodType, Collection<LocalDate> periodStarts);
}
