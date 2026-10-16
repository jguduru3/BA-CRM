package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.Patch;
import com.betteranalytics.crm.entity.ForecastQuota;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.pipeline.ForecastService;
import com.betteranalytics.crm.repository.ForecastQuotaRepository;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.TenantGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Weighted forecast by period and forecast category, plus simple per-user quotas.
 *
 *   GET /api/forecast?period=month|quarter&start=YYYY-MM-DD&periods=1..12&groupBy=none|owner|team
 *   GET /api/forecast/quotas?period=&start=     quotas of the users in the reader's scope
 *   PUT /api/forecast/quotas {userId, period, periodStart, amount|null}   Manager+ (own team)
 */
@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final ForecastService forecastService;
    private final ForecastQuotaRepository quotaRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final TenantGuard tenantGuard;

    public ForecastController(ForecastService forecastService, ForecastQuotaRepository quotaRepository,
                              UserRepository userRepository, PermissionService permissionService,
                              CurrentUser currentUser, AuditService auditService, TenantGuard tenantGuard) {
        this.forecastService = forecastService;
        this.quotaRepository = quotaRepository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping
    public Map<String, Object> forecast(@RequestParam(required = false) String period,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                        @RequestParam(required = false) Integer periods,
                                        @RequestParam(required = false) String groupBy) {
        return forecastService.forecast(currentUser.get(), period, start, periods, groupBy);
    }

    @GetMapping("/quotas")
    public List<Map<String, Object>> quotas(@RequestParam(required = false) String period,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        User actor = currentUser.get();
        String p = ForecastService.parsePeriod(period);
        LocalDate s = ForecastService.periodStart(p, start == null ? LocalDate.now() : start);
        boolean showValues = permissionService.canViewOpportunityValue(actor);
        Map<Long, User> users = new HashMap<>();
        userRepository.findByOrganizationId(actor.getOrganizationId()).forEach(u -> users.put(u.getId(), u));
        Set<Long> visible = forecastService.quotaUsers(actor, users.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (ForecastQuota q : quotaRepository.findByOrganizationIdAndPeriodTypeAndPeriodStartIn(actor.getOrganizationId(), p, List.of(s))) {
            if (!visible.contains(q.getUserId())) continue;
            out.add(toJson(q, users.get(q.getUserId()), showValues));
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return out;
    }

    @Transactional
    @PutMapping("/quotas")
    public Map<String, Object> setQuota(@RequestBody JsonNode body) {
        User actor = currentUser.get();
        Role r = actor.getRole();
        if (r != Role.SUPER_ADMIN && r != Role.VP_SALES && r != Role.MANAGER) {
            throw new AccessDeniedException("only a Manager or above can set forecast quotas");
        }
        Patch p = Patch.of(body);
        Long userId = p.lng("userId");
        if (userId == null) throw new IllegalArgumentException("userId is required");
        tenantGuard.requireUser(userId);
        if (!permissionService.canAssignTo(actor, userId)) {
            throw new AccessDeniedException("you can only set quotas for your own team");
        }
        String period = ForecastService.parsePeriod(p.str("period"));
        LocalDate start;
        try {
            start = p.hasValue("periodStart") ? LocalDate.parse(p.str("periodStart")) : null;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("periodStart must be YYYY-MM-DD");
        }
        if (start == null) throw new IllegalArgumentException("periodStart is required");
        if (!start.equals(ForecastService.periodStart(period, start))) {
            throw new IllegalArgumentException("periodStart must be the first day of the " + period);
        }
        Long amount = p.has("amount") ? p.lng("amount") : null;
        if (amount != null && amount < 0) throw new IllegalArgumentException("amount must not be negative");

        Optional<ForecastQuota> existing = quotaRepository
                .findByOrganizationIdAndUserIdAndPeriodTypeAndPeriodStart(actor.getOrganizationId(), userId, period, start);
        User target = userRepository.findByIdAndOrganizationId(userId, actor.getOrganizationId()).orElse(null);
        Map<String, Object> before = existing.map(q -> toJson(q, target, true)).orElse(null);
        if (amount == null) {
            existing.ifPresent(quotaRepository::delete);
            Map<String, Object> removed = new LinkedHashMap<>();
            removed.put("userId", userId);
            removed.put("period", period);
            removed.put("periodStart", start.toString());
            removed.put("amount", null);
            auditService.log("update", "forecast_quotas", userId, target == null ? null : target.getName(), before, removed);
            return removed;
        }
        ForecastQuota q = existing.orElseGet(() -> {
            ForecastQuota n = new ForecastQuota();
            n.setOrganizationId(actor.getOrganizationId());
            n.setUserId(userId);
            n.setPeriodType(period);
            n.setPeriodStart(start);
            n.setCreatedAt(Instant.now());
            return n;
        });
        q.setAmount(amount);
        q.setUpdatedBy(actor.getId());
        q.setUpdatedAt(Instant.now());
        quotaRepository.save(q);
        Map<String, Object> after = toJson(q, target, true);
        auditService.log("update", "forecast_quotas", userId, target == null ? null : target.getName(), before, after);
        return after;
    }

    private static Map<String, Object> toJson(ForecastQuota q, User u, boolean showValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", q.getUserId());
        m.put("name", u == null ? null : u.getName());
        m.put("period", q.getPeriodType());
        m.put("periodStart", q.getPeriodStart().toString());
        m.put("amount", showValues ? q.getAmount() : null);
        return m;
    }
}
