package com.betteranalytics.crm.pipeline;

import com.betteranalytics.crm.core.PipelineSettingsService;
import com.betteranalytics.crm.core.ScopeSpecs;
import com.betteranalytics.crm.entity.Opportunity;
import com.betteranalytics.crm.entity.OpportunityStageHistory;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pipeline analytics over the reader's record scope, all as GROUP BY queries:
 * <ul>
 *   <li>deal funnel (Pipedrive rule): a won deal counts as having passed every
 *       stage wherever it closed; a lost deal counts up to the stage it was lost
 *       in (the from-stage of its move to Lost); open deals are excluded unless
 *       {@code includeOpen} (then they count up to their current stage)</li>
 *   <li>win/loss by owner and by close reason</li>
 *   <li>velocity: average days spent in each stage (stage history) and average
 *       days from creation to Won</li>
 * </ul>
 * Amounts are null for roles that may not see deal values.
 */
@Service
public class PipelineAnalyticsService {

    /** Funnel columns: the open stages in order, then Won. */
    public static final List<String> FUNNEL_STAGES = List.of("Qualification", "Proposal", "Negotiation", "Verbal Commit", "Won");
    static final List<String> OPEN_STAGES = FUNNEL_STAGES.subList(0, 4);
    private static final double SECONDS_PER_DAY = 86_400.0;

    @PersistenceContext
    private EntityManager em;

    private final ScopeSpecs scopes;
    private final PermissionService permissionService;
    private final UserRepository userRepository;

    public PipelineAnalyticsService(ScopeSpecs scopes, PermissionService permissionService, UserRepository userRepository) {
        this.scopes = scopes;
        this.permissionService = permissionService;
        this.userRepository = userRepository;
    }

    /** Closed deals filtered on closedAt within [from, to] (inclusive days), when given. */
    private static Specification<Opportunity> closedBetween(LocalDate from, LocalDate to) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ZoneId zone = ZoneId.systemDefault();
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("closedAt"), from.atStartOfDay(zone).toInstant()));
            if (to != null) ps.add(cb.lessThan(root.get("closedAt"), to.plusDays(1).atStartOfDay(zone).toInstant()));
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(Predicate[]::new));
        };
    }

    private static <T> Specification<T> stageIs(String stage) {
        return (root, q, cb) -> cb.equal(root.get("stage"), stage);
    }

    // ── funnel ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> funnel(User actor, boolean includeOpen, LocalDate from, LocalDate to) {
        Specification<Opportunity> scope = scopes.opportunities(actor);
        long won = 0, lostTotal = 0, open = 0;
        long[] reached = new long[FUNNEL_STAGES.size()];
        if (scope != null) {
            won = count(scope.and(stageIs("Won")).and(closedBetween(from, to)));
            for (int i = 0; i < reached.length; i++) reached[i] += won;

            Specification<Opportunity> lostSpec = scope.and(stageIs("Lost")).and(closedBetween(from, to));
            lostTotal = count(lostSpec);
            Map<String, Long> lostAt = lostFromStage(lostSpec);
            long attributed = 0;
            for (Map.Entry<String, Long> e : lostAt.entrySet()) {
                int idx = e.getKey() == null ? 0 : Math.max(0, OPEN_STAGES.indexOf(e.getKey()));
                for (int i = 0; i <= idx; i++) reached[i] += e.getValue();
                attributed += e.getValue();
            }
            // lost deals with no recorded move to Lost: counted as lost in the first stage
            if (lostTotal > attributed) reached[0] += lostTotal - attributed;

            if (includeOpen) {
                Map<String, Long> openByStage = groupCount(scope.and((root, q, cb) ->
                        cb.not(root.get("stage").in(PipelineSettingsService.CLOSED_STAGES))), "stage");
                for (Map.Entry<String, Long> e : openByStage.entrySet()) {
                    int idx = Math.max(0, OPEN_STAGES.indexOf(e.getKey()));
                    for (int i = 0; i <= idx; i++) reached[i] += e.getValue();
                    open += e.getValue();
                }
            }
        }
        List<Map<String, Object>> stages = new ArrayList<>();
        for (int i = 0; i < reached.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", FUNNEL_STAGES.get(i));
            row.put("reached", reached[i]);
            row.put("conversionFromPrevious", i == 0 ? (reached[0] > 0 ? 100.0 : null) : pct(reached[i], reached[i - 1]));
            row.put("conversionFromStart", pct(reached[i], reached[0]));
            stages.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stages", stages);
        out.put("won", won);
        out.put("lost", lostTotal);
        out.put("open", open);
        out.put("includeOpen", includeOpen);
        out.put("winRate", pct(won, won + lostTotal));
        out.put("overallConversion", pct(won, reached[0]));
        return out;
    }

    /**
     * Lost deals grouped by the stage they were lost in: the from-stage of the
     * latest history row into Lost (correlated MAX(id) subquery).
     */
    private Map<String, Long> lostFromStage(Specification<Opportunity> lostSpec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<OpportunityStageHistory> h = q.from(OpportunityStageHistory.class);

        Subquery<Long> deals = q.subquery(Long.class);
        Root<Opportunity> o = deals.from(Opportunity.class);
        Predicate dealPredicate = lostSpec.toPredicate(o, q, cb);
        deals.select(o.get("id"));
        if (dealPredicate != null) deals.where(dealPredicate);

        Subquery<Long> latest = q.subquery(Long.class);
        Root<OpportunityStageHistory> h2 = latest.from(OpportunityStageHistory.class);
        latest.select(cb.max(h2.get("id")))
              .where(cb.equal(h2.get("opportunityId"), h.get("opportunityId")), cb.equal(h2.get("toStage"), "Lost"));

        q.multiselect(h.get("fromStage"), cb.count(h));
        q.where(cb.equal(h.get("toStage"), "Lost"), h.get("opportunityId").in(deals), cb.equal(h.get("id"), latest));
        q.groupBy(h.get("fromStage"));
        Map<String, Long> out = new HashMap<>();
        for (Object[] r : em.createQuery(q).getResultList()) {
            out.merge((String) r[0], ((Number) r[1]).longValue(), Long::sum);
        }
        return out;
    }

    // ── win / loss ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> winLoss(User actor, LocalDate from, LocalDate to) {
        Specification<Opportunity> scope = scopes.opportunities(actor);
        boolean showValues = permissionService.canViewOpportunityValue(actor);
        List<Map<String, Object>> byOwner = new ArrayList<>();
        List<Map<String, Object>> byReason = new ArrayList<>();
        long won = 0, lost = 0, wonValue = 0, lostValue = 0;
        if (scope != null) {
            Specification<Opportunity> closed = scope
                    .and((root, q, cb) -> root.get("stage").in(PipelineSettingsService.CLOSED_STAGES))
                    .and(closedBetween(from, to));
            Map<Long, User> users = userRepository.findByOrganizationId(actor.getOrganizationId()).stream()
                    .collect(Collectors.toMap(User::getId, u -> u));

            Map<Long, long[]> owners = new LinkedHashMap<>(); // [won, lost, wonValue, lostValue]
            for (Object[] r : group(closed, "ownerId", "stage")) {
                long[] acc = owners.computeIfAbsent((Long) r[0], k -> new long[4]);
                boolean isWon = "Won".equals(r[1]);
                acc[isWon ? 0 : 1] += ((Number) r[2]).longValue();
                acc[isWon ? 2 : 3] += ((Number) r[3]).longValue();
            }
            for (Map.Entry<Long, long[]> e : owners.entrySet()) {
                long[] a = e.getValue();
                User u = e.getKey() == null ? null : users.get(e.getKey());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ownerId", e.getKey());
                row.put("name", u != null ? u.getName() : "Unassigned");
                row.put("color", u != null ? u.getColor() : null);
                row.put("won", a[0]);
                row.put("lost", a[1]);
                row.put("wonValue", showValues ? a[2] : null);
                row.put("lostValue", showValues ? a[3] : null);
                row.put("winRate", pct(a[0], a[0] + a[1]));
                byOwner.add(row);
                won += a[0];
                lost += a[1];
                wonValue += a[2];
                lostValue += a[3];
            }
            byOwner.sort(Comparator.comparing((Map<String, Object> m) -> -((Long) m.get("won") + (Long) m.get("lost")))
                    .thenComparing(m -> String.valueOf(m.get("name"))));

            for (Object[] r : group(closed, "stage", "closeReason")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("outcome", r[0]);
                String reason = r[1] == null ? null : String.valueOf(r[1]).trim();
                row.put("reason", reason == null || reason.isEmpty() ? "No reason given" : reason);
                row.put("count", ((Number) r[2]).longValue());
                row.put("value", showValues ? ((Number) r[3]).longValue() : null);
                byReason.add(row);
            }
            byReason.sort(Comparator.comparing((Map<String, Object> m) -> -(Long) m.get("count"))
                    .thenComparing(m -> String.valueOf(m.get("reason"))));
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("won", won);
        totals.put("lost", lost);
        totals.put("wonValue", showValues ? wonValue : null);
        totals.put("lostValue", showValues ? lostValue : null);
        totals.put("winRate", pct(won, won + lost));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byOwner", byOwner);
        out.put("byReason", byReason);
        out.put("totals", totals);
        out.put("valuesVisible", showValues);
        return out;
    }

    // ── velocity ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> velocity(User actor, LocalDate from, LocalDate to) {
        Specification<Opportunity> scope = scopes.opportunities(actor);
        Map<String, double[]> byStage = new HashMap<>(); // [avgSeconds, samples]
        Double daysToWon = null;
        long wonSamples = 0;
        if (scope != null) {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
            Root<OpportunityStageHistory> h = q.from(OpportunityStageHistory.class);
            List<Predicate> where = new ArrayList<>();
            where.add(h.get("opportunityId").in(dealIds(q, cb, scope)));
            where.add(cb.isNotNull(h.get("fromStage")));
            where.add(cb.isNotNull(h.get("secondsInFromStage")));
            where.addAll(enteredBetween(cb, h, from, to));
            q.multiselect(h.get("fromStage"), cb.avg(h.get("secondsInFromStage")), cb.count(h));
            q.where(where.toArray(Predicate[]::new));
            q.groupBy(h.get("fromStage"));
            for (Object[] r : em.createQuery(q).getResultList()) {
                byStage.put((String) r[0], new double[]{((Number) r[1]).doubleValue(), ((Number) r[2]).doubleValue()});
            }

            // days to close won: the latest move into Won of each currently-won deal
            CriteriaQuery<Object[]> w = cb.createQuery(Object[].class);
            Root<OpportunityStageHistory> hw = w.from(OpportunityStageHistory.class);
            Subquery<Long> latest = w.subquery(Long.class);
            Root<OpportunityStageHistory> h2 = latest.from(OpportunityStageHistory.class);
            latest.select(cb.max(h2.get("id")))
                  .where(cb.equal(h2.get("opportunityId"), hw.get("opportunityId")), cb.equal(h2.get("toStage"), "Won"));
            List<Predicate> wWhere = new ArrayList<>();
            wWhere.add(cb.equal(hw.get("toStage"), "Won"));
            wWhere.add(hw.get("opportunityId").in(dealIds(w, cb, scope.and(stageIs("Won")))));
            wWhere.add(cb.equal(hw.get("id"), latest));
            wWhere.add(cb.isNotNull(hw.get("secondsSinceCreated")));
            wWhere.addAll(enteredBetween(cb, hw, from, to));
            w.multiselect(cb.avg(hw.get("secondsSinceCreated")), cb.count(hw));
            w.where(wWhere.toArray(Predicate[]::new));
            Object[] r = em.createQuery(w).getSingleResult();
            wonSamples = r[1] == null ? 0 : ((Number) r[1]).longValue();
            if (wonSamples > 0 && r[0] != null) daysToWon = round1(((Number) r[0]).doubleValue() / SECONDS_PER_DAY);
        }
        List<Map<String, Object>> stages = new ArrayList<>();
        for (String stage : OPEN_STAGES) {
            double[] v = byStage.get(stage);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            row.put("avgDays", v == null ? null : round1(v[0] / SECONDS_PER_DAY));
            row.put("samples", v == null ? 0L : (long) v[1]);
            stages.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stages", stages);
        out.put("avgDaysToCloseWon", daysToWon);
        out.put("wonSamples", wonSamples);
        return out;
    }

    private static List<Predicate> enteredBetween(CriteriaBuilder cb, Root<OpportunityStageHistory> h, LocalDate from, LocalDate to) {
        List<Predicate> ps = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        if (from != null) ps.add(cb.greaterThanOrEqualTo(h.<Instant>get("enteredAt"), from.atStartOfDay(zone).toInstant()));
        if (to != null) ps.add(cb.lessThan(h.<Instant>get("enteredAt"), to.plusDays(1).atStartOfDay(zone).toInstant()));
        return ps;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private Subquery<Long> dealIds(CriteriaQuery<?> q, CriteriaBuilder cb, Specification<Opportunity> spec) {
        Subquery<Long> deals = q.subquery(Long.class);
        Root<Opportunity> o = deals.from(Opportunity.class);
        Predicate p = spec.toPredicate(o, q, cb);
        deals.select(o.get("id"));
        if (p != null) deals.where(p);
        return deals;
    }

    private long count(Specification<Opportunity> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<Opportunity> o = q.from(Opportunity.class);
        q.select(cb.count(o));
        Predicate p = spec.toPredicate(o, q, cb);
        if (p != null) q.where(p);
        return em.createQuery(q).getSingleResult();
    }

    private Map<String, Long> groupCount(Specification<Opportunity> spec, String attr) {
        Map<String, Long> out = new HashMap<>();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<Opportunity> o = q.from(Opportunity.class);
        q.multiselect(o.get(attr), cb.count(o));
        Predicate p = spec.toPredicate(o, q, cb);
        if (p != null) q.where(p);
        q.groupBy(o.get(attr));
        for (Object[] r : em.createQuery(q).getResultList()) out.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        return out;
    }

    /** [a, b, count, sum(value)] grouped by two attributes. */
    private List<Object[]> group(Specification<Opportunity> spec, String a, String b) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<Opportunity> o = q.from(Opportunity.class);
        q.multiselect(o.get(a), o.get(b), cb.count(o), cb.coalesce(cb.sum(o.<Long>get("value")), 0L));
        Predicate p = spec.toPredicate(o, q, cb);
        if (p != null) q.where(p);
        q.groupBy(o.get(a), o.get(b));
        return em.createQuery(q).getResultList();
    }

    static Double pct(long part, long whole) {
        return whole <= 0 ? null : round1(part * 100.0 / whole);
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
