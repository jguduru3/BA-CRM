package com.betteranalytics.crm.pipeline;

import com.betteranalytics.crm.core.PipelineSettingsService;
import com.betteranalytics.crm.core.ScopeSpecs;
import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.ForecastQuotaRepository;
import com.betteranalytics.crm.repository.TeamMemberRepository;
import com.betteranalytics.crm.repository.TeamRepository;
import com.betteranalytics.crm.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Weighted forecast by period and forecast category (HubSpot model).
 *
 * Deals are bucketed by close date; within a bucket each category gets a count,
 * the sum of deal value and the weighted sum (value × probability / 100). The
 * roll-up "Commit + Closed won" is compared with the quota of the users in view.
 * All sums are GROUP BY queries over the reader's record scope. Roles that may
 * not see deal values (Inside Sales) get counts only: every amount is null.
 */
@Service
public class ForecastService {

    public static final List<String> PERIODS = List.of("month", "quarter");
    public static final List<String> GROUP_BYS = List.of("none", "owner", "team");
    static final int MAX_PERIODS = 12;
    /** Roles listed in the per-owner forecast even without deals (quota targets). */
    static final Set<Role> SELLER_ROLES = EnumSet.of(Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.FIELD_SALES);

    @PersistenceContext
    private EntityManager em;

    private final ScopeSpecs scopes;
    private final PermissionService permissionService;
    private final PipelineSettingsService pipelineSettings;
    private final ForecastQuotaRepository quotaRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;

    public ForecastService(ScopeSpecs scopes, PermissionService permissionService, PipelineSettingsService pipelineSettings,
                           ForecastQuotaRepository quotaRepository, UserRepository userRepository,
                           TeamRepository teamRepository, TeamMemberRepository teamMemberRepository) {
        this.scopes = scopes;
        this.permissionService = permissionService;
        this.pipelineSettings = pipelineSettings;
        this.quotaRepository = quotaRepository;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    // ── periods ──────────────────────────────────────────────────────────────────

    public static String parsePeriod(String period) {
        String p = period == null ? "month" : period.trim().toLowerCase(Locale.ROOT);
        if (!PERIODS.contains(p)) throw new IllegalArgumentException("period must be one of " + PERIODS);
        return p;
    }

    /** First day of the month / quarter containing {@code date}. */
    public static LocalDate periodStart(String period, LocalDate date) {
        LocalDate first = date.withDayOfMonth(1);
        if (period.equals("quarter")) first = first.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1);
        return first;
    }

    public static LocalDate nextPeriod(String period, LocalDate start) {
        return period.equals("quarter") ? start.plusMonths(3) : start.plusMonths(1);
    }

    static String label(String period, LocalDate start) {
        if (period.equals("quarter")) return "Q" + ((start.getMonthValue() - 1) / 3 + 1) + " " + start.getYear();
        return start.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + start.getYear();
    }

    // ── forecast ─────────────────────────────────────────────────────────────────

    /** One aggregated cell: [count, value, weighted] — value/weighted null when masked. */
    private static final class Cell {
        long count;
        long value;
        long weightedTimes100; // sum(value × probability), divided by 100 at the end

        void add(long c, long v, long w) {
            count += c;
            value += v;
            weightedTimes100 += w;
        }

        void add(Cell o) {
            add(o.count, o.value, o.weightedTimes100);
        }
    }

    private record Row(Long ownerId, ForecastCategory category, long count, long value, long weighted) {}

    @Transactional(readOnly = true)
    public Map<String, Object> forecast(User actor, String periodParam, LocalDate startParam, Integer periodsParam,
                                        String groupByParam) {
        String period = parsePeriod(periodParam);
        String groupBy = groupByParam == null ? "none" : groupByParam.trim().toLowerCase(Locale.ROOT);
        if (!GROUP_BYS.contains(groupBy)) throw new IllegalArgumentException("groupBy must be one of " + GROUP_BYS);
        int periods = periodsParam == null ? 1 : periodsParam;
        if (periods < 1 || periods > MAX_PERIODS) throw new IllegalArgumentException("periods must be between 1 and " + MAX_PERIODS);
        LocalDate start = periodStart(period, startParam == null ? LocalDate.now() : startParam);

        Specification<Opportunity> scope = scopes.opportunities(actor);
        if (scope == null) throw new AccessDeniedException("not permitted: " + actor.getRole() + " cannot VIEW opportunities");
        boolean showValues = permissionService.canViewOpportunityValue(actor);
        PipelineSettingsService.Settings settings = pipelineSettings.get(actor.getOrganizationId());

        // users and teams for names / grouping / quota
        Map<Long, User> users = userRepository.findByOrganizationId(actor.getOrganizationId()).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        Map<Long, Team> primaryTeam = groupBy.equals("team") ? primaryTeams(actor.getOrganizationId()) : Map.of();
        Set<Long> quotaUsers = quotaUsers(actor, users.keySet());

        List<LocalDate> starts = new ArrayList<>();
        LocalDate s = start;
        for (int i = 0; i < periods; i++) {
            starts.add(s);
            s = nextPeriod(period, s);
        }
        Map<LocalDate, Map<Long, Long>> quotas = new HashMap<>();
        for (ForecastQuota q : quotaRepository.findByOrganizationIdAndPeriodTypeAndPeriodStartIn(actor.getOrganizationId(), period, starts)) {
            if (!quotaUsers.contains(q.getUserId())) continue;
            quotas.computeIfAbsent(q.getPeriodStart(), k -> new HashMap<>()).merge(q.getUserId(), q.getAmount(), Long::sum);
        }

        List<Map<String, Object>> buckets = new ArrayList<>();
        for (LocalDate bStart : starts) {
            LocalDate bNext = nextPeriod(period, bStart);
            List<Row> rows = aggregate(scope, bStart, bNext, settings);
            Map<Long, Long> bucketQuotas = quotas.getOrDefault(bStart, Map.of());

            Map<ForecastCategory, Cell> totals = new EnumMap<>(ForecastCategory.class);
            Map<Object, Map<ForecastCategory, Cell>> groups = new LinkedHashMap<>();
            for (Row r : rows) {
                totals.computeIfAbsent(r.category(), k -> new Cell()).add(r.count(), r.value(), r.weighted());
                if (!groupBy.equals("none")) {
                    Object key = groupKey(groupBy, r.ownerId(), primaryTeam);
                    groups.computeIfAbsent(key, k -> new EnumMap<>(ForecastCategory.class))
                            .computeIfAbsent(r.category(), k -> new Cell()).add(r.count(), r.value(), r.weighted());
                }
            }
            Long bucketQuota = bucketQuotas.isEmpty() ? null : bucketQuotas.values().stream().mapToLong(Long::longValue).sum();

            Map<String, Object> b = new LinkedHashMap<>();
            b.put("start", bStart.toString());
            b.put("end", bNext.minusDays(1).toString());
            b.put("label", label(period, bStart));
            putFigures(b, totals, bucketQuota, showValues);

            List<Map<String, Object>> groupRows = new ArrayList<>();
            if (!groupBy.equals("none")) {
                // groups with a quota but no deals still show (0 of quota); by owner, every
                // active seller in view is listed so a manager can set quotas before deals exist
                for (Long uid : bucketQuotas.keySet()) groups.computeIfAbsent(groupKey(groupBy, uid, primaryTeam), k -> new EnumMap<>(ForecastCategory.class));
                if (groupBy.equals("owner")) {
                    for (Long uid : quotaUsers) {
                        User u = users.get(uid);
                        if (u != null && u.isActive() && SELLER_ROLES.contains(u.getRole())) {
                            groups.computeIfAbsent(uid, k -> new EnumMap<>(ForecastCategory.class));
                        }
                    }
                }
                for (Map.Entry<Object, Map<ForecastCategory, Cell>> g : groups.entrySet()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Object key = g.getKey();
                    Long quota = null;
                    if (groupBy.equals("owner")) {
                        Long uid = key instanceof Long l ? l : null;
                        User u = uid == null ? null : users.get(uid);
                        row.put("id", uid);
                        row.put("name", u != null ? u.getName() : "Unassigned");
                        row.put("initials", u != null ? u.getInitials() : null);
                        row.put("color", u != null ? u.getColor() : null);
                        quota = uid == null ? null : bucketQuotas.get(uid);
                    } else {
                        Team t = key instanceof Team tt ? tt : null;
                        row.put("id", t == null ? null : t.getId());
                        row.put("name", t == null ? "No team" : t.getName());
                        row.put("color", t == null ? null : t.getColor());
                        long sum = 0;
                        boolean any = false;
                        for (Map.Entry<Long, Long> q : bucketQuotas.entrySet()) {
                            if (Objects.equals(groupKey("team", q.getKey(), primaryTeam), key)) {
                                sum += q.getValue();
                                any = true;
                            }
                        }
                        quota = any ? sum : null;
                    }
                    putFigures(row, g.getValue(), quota, showValues);
                    groupRows.add(row);
                }
                groupRows.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("name"))));
            }
            b.put("groups", groupRows);
            buckets.add(b);
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        for (ForecastCategory c : ForecastCategory.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", c.name());
            row.put("label", c.label());
            row.put("order", c.ordinal());
            categories.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("start", start.toString());
        out.put("periods", periods);
        out.put("groupBy", groupBy);
        out.put("valuesVisible", showValues);
        out.put("categories", categories);
        out.put("buckets", buckets);
        return out;
    }

    private static Object groupKey(String groupBy, Long ownerId, Map<Long, Team> primaryTeam) {
        if (groupBy.equals("team")) {
            Team t = ownerId == null ? null : primaryTeam.get(ownerId);
            return t == null ? "none" : t;
        }
        return ownerId == null ? "unassigned" : ownerId;
    }

    private static void putFigures(Map<String, Object> out, Map<ForecastCategory, Cell> cells, Long quota, boolean showValues) {
        Map<String, Object> totals = new LinkedHashMap<>();
        Cell all = new Cell();
        for (ForecastCategory c : ForecastCategory.values()) {
            Cell cell = cells.getOrDefault(c, new Cell());
            all.add(cell);
            totals.put(c.name(), cellJson(cell, showValues));
        }
        out.put("totals", totals);
        out.put("total", cellJson(all, showValues));
        // cumulative roll-up: a category plus every higher category (by total value)
        Map<String, Object> cumulative = new LinkedHashMap<>();
        Map<String, Object> cumulativeWeighted = new LinkedHashMap<>();
        long running = 0, runningW = 0;
        ForecastCategory[] ordered = ForecastCategory.values();
        for (int i = ordered.length - 1; i >= 0; i--) {
            Cell cell = cells.getOrDefault(ordered[i], new Cell());
            running += cell.value;
            runningW += cell.weightedTimes100;
            cumulative.put(ordered[i].name(), showValues ? running : null);
            cumulativeWeighted.put(ordered[i].name(), showValues ? Math.round(runningW / 100.0) : null);
        }
        out.put("cumulative", cumulative);
        out.put("cumulativeWeighted", cumulativeWeighted);
        long commitAndWon = cells.getOrDefault(ForecastCategory.COMMIT, new Cell()).value
                + cells.getOrDefault(ForecastCategory.CLOSED_WON, new Cell()).value;
        long won = cells.getOrDefault(ForecastCategory.CLOSED_WON, new Cell()).value;
        out.put("commitAndWon", showValues ? commitAndWon : null);
        out.put("closedWon", showValues ? won : null);
        out.put("quota", showValues ? quota : null);
        out.put("attainment", showValues && quota != null && quota > 0 ? round1(commitAndWon * 100.0 / quota) : null);
        out.put("wonAttainment", showValues && quota != null && quota > 0 ? round1(won * 100.0 / quota) : null);
    }

    private static Map<String, Object> cellJson(Cell c, boolean showValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", c.count);
        m.put("value", showValues ? c.value : null);
        m.put("weighted", showValues ? Math.round(c.weightedTimes100 / 100.0) : null);
        return m;
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * SELECT owner, category, stage, COUNT, SUM(value), SUM(value × probability)
     * WHERE scope AND close_date in [from, to) GROUP BY owner, category, stage.
     * A deal without a stored category (legacy / repaired nightly) uses its stage's.
     */
    private List<Row> aggregate(Specification<Opportunity> scope, LocalDate from, LocalDate to,
                                PipelineSettingsService.Settings settings) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<Opportunity> o = q.from(Opportunity.class);
        Expression<Long> value = o.get("value");
        Expression<Integer> probability = o.get("probability");
        q.multiselect(o.get("ownerId"), o.get("forecastCategory"), o.get("stage"), cb.count(o),
                cb.coalesce(cb.sum(value), 0L),
                cb.coalesce(cb.sum(cb.prod(value, cb.coalesce(probability, 0).as(Long.class))), 0L));
        Predicate p = scope.toPredicate(o, q, cb);
        Predicate inBucket = cb.and(cb.greaterThanOrEqualTo(o.get("closeDate"), from), cb.lessThan(o.get("closeDate"), to));
        q.where(p == null ? inBucket : cb.and(p, inBucket));
        q.groupBy(o.get("ownerId"), o.get("forecastCategory"), o.get("stage"));
        List<Row> out = new ArrayList<>();
        for (Object[] r : em.createQuery(q).getResultList()) {
            ForecastCategory cat = r[1] instanceof ForecastCategory c ? c : settings.categoryFor(String.valueOf(r[2]));
            out.add(new Row((Long) r[0], cat, ((Number) r[3]).longValue(),
                    ((Number) r[4]).longValue(), ((Number) r[5]).longValue()));
        }
        return out;
    }

    /** Owner → first team (lowest id) they are a member of or lead; owners with no team are absent. */
    private Map<Long, Team> primaryTeams(Long orgId) {
        List<Team> teams = teamRepository.findByOrganizationId(orgId).stream()
                .filter(Team::isActive).sorted(Comparator.comparing(Team::getId)).toList();
        Map<Long, Team> byUser = new HashMap<>();
        if (teams.isEmpty()) return byUser;
        Map<Long, Team> byId = teams.stream().collect(Collectors.toMap(Team::getId, t -> t));
        List<TeamMember> members = teamMemberRepository.findByTeamIdIn(byId.keySet());
        members.sort(Comparator.comparing(TeamMember::getTeamId));
        for (TeamMember m : members) byUser.putIfAbsent(m.getUserId(), byId.get(m.getTeamId()));
        for (Team t : teams) if (t.getLeaderId() != null) byUser.putIfAbsent(t.getLeaderId(), t);
        return byUser;
    }

    /** Users whose quotas count for the actor: the opportunity VIEW scope (or just themselves). */
    public Set<Long> quotaUsers(User actor, Set<Long> orgUsers) {
        OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, ResourceType.OPPORTUNITY);
        if (scope.all()) return orgUsers;
        Set<Long> ids = new HashSet<>(scope.ownerIds());
        ids.add(actor.getId());
        ids.retainAll(orgUsers);
        return ids;
    }
}
