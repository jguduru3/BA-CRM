package com.betteranalytics.crm.pipeline;

import com.betteranalytics.crm.core.PipelineSettingsService;
import com.betteranalytics.crm.entity.Opportunity;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.OpportunityStageHistoryRepository;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Rules-first, explainable deal health (0–100). Every open deal starts at 100
 * and each factor deducts points with a human-readable reason:
 * <ul>
 *   <li>stage_time — days in the current stage vs the org's median for that stage
 *       (from stage history; a default benchmark until 3 samples exist)</li>
 *   <li>close_date — close-date slips (pushed later) and a passed close date</li>
 *   <li>activity — days since the last activity logged on the deal</li>
 *   <li>contact — no contact person on the deal</li>
 *   <li>proposal — proposal not sent although the deal is past Qualification</li>
 *   <li>overdue_tasks — open tasks on the deal past their due date</li>
 * </ul>
 * Risk flags (stalled stage, slipped close date, no activity for 14 days, no
 * contact) are stored with the score; a newly raised flag notifies the owner and
 * the owner's manager once (never the user whose edit raised it).
 */
@Service
public class DealHealthService {

    public enum Flag { STALLED_STAGE, SLIPPED_CLOSE_DATE, NO_ACTIVITY, NO_CONTACT }

    public record Factor(String key, String label, int points, String detail) {}

    public record Health(Integer score, List<Factor> factors, Map<Flag, String> flags) {
        static final Health CLOSED = new Health(null, List.of(), Map.of());
    }

    /** Typical days per stage until the org has enough history of its own. */
    static final Map<String, Double> DEFAULT_BENCHMARK_DAYS = Map.of(
            "Qualification", 14.0, "Proposal", 21.0, "Negotiation", 21.0, "Verbal Commit", 14.0);
    static final long MIN_SAMPLES = 3;
    static final int NO_ACTIVITY_DAYS = 14;
    static final int NO_CONTACT_GRACE_DAYS = 2;
    static final List<String> PROPOSAL_STAGES = List.of("Proposal", "Negotiation", "Verbal Commit");

    @PersistenceContext
    private EntityManager em;

    private final OpportunityStageHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public DealHealthService(OpportunityStageHistoryRepository historyRepository, UserRepository userRepository,
                             NotificationService notificationService, ObjectMapper objectMapper) {
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    /** Median days per stage for the org (defaults where there is too little history). */
    public Map<String, Double> benchmarks(Long orgId) {
        Map<String, Double> out = new HashMap<>(DEFAULT_BENCHMARK_DAYS);
        for (Object[] row : historyRepository.medianDaysByStage(orgId, MIN_SAMPLES)) {
            if (row[0] == null || row[1] == null) continue;
            double median = ((Number) row[1]).doubleValue();
            if (median > 0) out.put(String.valueOf(row[0]), median);
        }
        return out;
    }

    /** Evaluates a deal, loading its activity / task signals from the database. */
    public Health evaluate(Opportunity o, Instant now, Map<String, Double> benchmarks) {
        if (PipelineSettingsService.CLOSED_STAGES.contains(o.getStage())) return Health.CLOSED;
        Instant lastActivity = null;
        long overdueTasks = 0;
        if (o.getId() != null) {
            lastActivity = em.createQuery(
                            "SELECT MAX(a.createdAt) FROM Activity a WHERE a.organizationId = :org " +
                            "AND LOWER(a.relatedType) = 'opportunity' AND a.relatedId = :id", Instant.class)
                    .setParameter("org", o.getOrganizationId()).setParameter("id", o.getId())
                    .getSingleResult();
            overdueTasks = em.createQuery(
                            "SELECT COUNT(t) FROM Task t WHERE t.organizationId = :org " +
                            "AND LOWER(t.relatedType) = 'opportunity' AND t.relatedId = :id " +
                            "AND t.status <> 'Done' AND t.dueDate < :today", Long.class)
                    .setParameter("org", o.getOrganizationId()).setParameter("id", o.getId())
                    .setParameter("today", LocalDate.ofInstant(now, ZoneId.systemDefault()))
                    .getSingleResult();
        }
        return evaluate(o, now, benchmarks, lastActivity, overdueTasks);
    }

    /** Pure scoring over already-loaded signals. */
    Health evaluate(Opportunity o, Instant now, Map<String, Double> benchmarks, Instant lastActivity, long overdueTasks) {
        if (PipelineSettingsService.CLOSED_STAGES.contains(o.getStage())) return Health.CLOSED;
        List<Factor> factors = new ArrayList<>();
        Map<Flag, String> flags = new EnumMap<>(Flag.class);
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        Instant created = o.getCreatedAt() != null ? o.getCreatedAt() : now;
        long ageDays = days(created, now);

        // 1. time in the current stage vs the org's median
        Instant enteredAt = o.getStageEnteredAt() != null ? o.getStageEnteredAt() : created;
        long inStage = days(enteredAt, now);
        double usual = benchmarks.getOrDefault(o.getStage(), DEFAULT_BENCHMARK_DAYS.getOrDefault(o.getStage(), 14.0));
        String usualText = formatDays(usual);
        String stage = o.getStage();
        if (inStage > 2 * usual && inStage >= 7) {
            String why = inStage + " days in " + stage + ", more than twice the usual " + usualText;
            factors.add(new Factor("stage_time", "Stalled in stage", -25, why));
            flags.put(Flag.STALLED_STAGE, "Stalled: " + why);
        } else if (inStage > 1.5 * usual) {
            factors.add(new Factor("stage_time", "Slow in stage", -15, inStage + " days in " + stage + " (usual " + usualText + ")"));
        } else if (inStage > usual) {
            factors.add(new Factor("stage_time", "Slightly slow", -5, inStage + " days in " + stage + " (usual " + usualText + ")"));
        } else {
            factors.add(new Factor("stage_time", "On pace", 0, inStage + " " + plural(inStage, "day") + " in " + stage + " (usual " + usualText + ")"));
        }

        // 2. close date: slips and a passed date
        int slips = o.getCloseDateSlips();
        int closePoints = 0;
        List<String> closeWhy = new ArrayList<>();
        if (slips > 0) {
            closePoints -= Math.min(30, 10 * slips);
            closeWhy.add("pushed back " + slips + " " + plural(slips, "time"));
        }
        boolean overdue = o.getCloseDate() != null && o.getCloseDate().isBefore(today);
        if (overdue) {
            closePoints -= 20;
            closeWhy.add("expected close date passed (" + o.getCloseDate() + ")");
        }
        if (o.getCloseDate() == null) {
            closePoints -= 5;
            closeWhy.add("no expected close date");
        }
        if (closeWhy.isEmpty()) {
            factors.add(new Factor("close_date", "Close date on track", 0, "Expected close " + o.getCloseDate()));
        } else {
            String why = capitalise(String.join("; ", closeWhy));
            factors.add(new Factor("close_date", "Close date", closePoints, why));
            if (slips > 0 || overdue) flags.put(Flag.SLIPPED_CLOSE_DATE, "Close date slipping: " + why);
        }

        // 3. activity recency
        long quietDays = lastActivity != null ? days(lastActivity, now) : ageDays;
        String quietWhy = lastActivity != null
                ? "Last activity " + quietDays + " " + plural(quietDays, "day") + " ago"
                : quietDays == 0 ? "No activity logged yet (new deal)"
                : "No activity logged since the deal was created " + quietDays + " " + plural(quietDays, "day") + " ago";
        int activityPoints = quietDays >= 30 ? -25 : quietDays >= NO_ACTIVITY_DAYS ? -15 : quietDays >= 7 ? -5 : 0;
        factors.add(new Factor("activity", activityPoints == 0 ? "Recent activity" : "Gone quiet", activityPoints, quietWhy));
        if (quietDays >= NO_ACTIVITY_DAYS) flags.put(Flag.NO_ACTIVITY, quietWhy);

        // 4. contact person
        if (o.getContactId() == null) {
            factors.add(new Factor("contact", "No contact", -15, "No contact person on the deal"));
            if (ageDays >= NO_CONTACT_GRACE_DAYS) flags.put(Flag.NO_CONTACT, "No contact person on the deal");
        } else {
            factors.add(new Factor("contact", "Contact linked", 0, "A contact person is linked"));
        }

        // 5. proposal status (only once the deal is past Qualification)
        if (PROPOSAL_STAGES.contains(stage)) {
            String ps = o.getProposalStatus();
            if (ps == null || ps.isBlank() || ps.equalsIgnoreCase("Not Sent")) {
                factors.add(new Factor("proposal", "Proposal not sent", -10, "In " + stage + " but the proposal has not been sent"));
            } else {
                factors.add(new Factor("proposal", "Proposal " + ps.toLowerCase(Locale.ROOT), 0, "Proposal status: " + ps));
            }
        }

        // 6. overdue tasks on the deal
        if (overdueTasks > 0) {
            factors.add(new Factor("overdue_tasks", "Overdue tasks", (int) -Math.min(20, 10 * overdueTasks),
                    overdueTasks + " overdue " + plural(overdueTasks, "task")));
        } else {
            factors.add(new Factor("overdue_tasks", "Tasks on time", 0, "No overdue tasks"));
        }

        int score = 100 + factors.stream().mapToInt(Factor::points).sum();
        return new Health(Math.max(0, Math.min(100, score)), factors, flags);
    }

    /** Writes the result onto the (managed or new) entity. */
    public void apply(Opportunity o, Health h, Instant now) {
        o.setHealthScore(h.score());
        o.setHealthFactors(h.score() == null ? null : factorsJson(h));
        o.setHealthFlags(flagsString(h.flags().keySet()));
        o.setHealthComputedAt(now);
    }

    public String factorsJson(Health h) {
        try {
            return objectMapper.writeValueAsString(h.factors());
        } catch (Exception e) {
            return "[]";
        }
    }

    public static String flagsString(Collection<Flag> flags) {
        if (flags == null || flags.isEmpty()) return null;
        return flags.stream().sorted().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(null);
    }

    public static Set<Flag> parseFlags(String s) {
        Set<Flag> out = EnumSet.noneOf(Flag.class);
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(",")) {
            try {
                out.add(Flag.valueOf(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // unknown legacy flag
            }
        }
        return out;
    }

    /**
     * Notifies the owner and the owner's manager about each flag in {@code h} that
     * was not raised before. The actor (whose edit raised it) is never notified.
     * @return notifications sent
     */
    public int notifyRaised(Opportunity o, Set<Flag> previous, Health h, Long actorId) {
        List<Flag> raised = h.flags().keySet().stream().filter(f -> !previous.contains(f)).sorted().toList();
        if (raised.isEmpty() || o.getOwnerId() == null) return 0;
        Set<Long> recipients = new LinkedHashSet<>();
        User owner = userRepository.findByIdAndOrganizationId(o.getOwnerId(), o.getOrganizationId()).orElse(null);
        if (owner != null) {
            if (owner.isActive()) recipients.add(owner.getId());
            if (owner.getReportsToId() != null) {
                userRepository.findByIdAndOrganizationId(owner.getReportsToId(), o.getOrganizationId())
                        .filter(User::isActive).ifPresent(m -> recipients.add(m.getId()));
            }
        }
        if (actorId != null) recipients.remove(actorId);
        int sent = 0;
        for (Flag f : raised) {
            for (Long uid : recipients) {
                notificationService.notify(o.getOrganizationId(), uid, "deal_risk", "Deal at risk: " + o.getName(),
                        h.flags().get(f), "Opportunity", o.getId());
                sent++;
            }
        }
        return sent;
    }

    private static long days(Instant from, Instant to) {
        return Math.max(0L, Duration.between(from, to).toDays());
    }

    private static String formatDays(double d) {
        if (d >= 1) {
            long r = Math.round(d);
            return r + " " + plural(r, "day");
        }
        return String.format(Locale.ROOT, "%.1f days", d);
    }

    private static String plural(long n, String word) {
        return n == 1 ? word : word + "s";
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
