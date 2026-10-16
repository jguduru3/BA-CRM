package com.betteranalytics.crm.routing;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.TeamMember;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.TeamMemberRepository;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.security.ApiKeyAuthFilter;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.*;

/**
 * Round-robin lead routing. A new lead without an owner that is created through
 * the API (an API key) — or by a Manager+ who leaves the owner empty — goes to
 * the next eligible rep after the organization's rotation pointer:
 * <ul>
 *   <li>eligible = active EXECUTIVE / INSIDE_SALES / FIELD_SALES users of the org,
 *       or of the configured team; inactive users are skipped;</li>
 *   <li>with respectTerritory, FIELD_SALES reps only qualify when the lead's
 *       geography contains their territory (same rule as claiming from the pool);</li>
 *   <li>the pointer moves with a compare-and-set UPDATE in its own short transaction,
 *       retried on conflict, so concurrent creations never hand two leads the same
 *       turn and the rotation stays exact under load.</li>
 * </ul>
 */
@Service
public class RoutingService {

    static final Set<Role> REP_ROLES = EnumSet.of(Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
    private static final int MAX_ATTEMPTS = 50;

    private final RoutingSettingsRepository repository;
    private final UserRepository userRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TransactionTemplate requiresNew;

    public RoutingService(RoutingSettingsRepository repository, UserRepository userRepository,
                          TeamMemberRepository teamMemberRepository, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Stored settings, or the (disabled) defaults when the org never saved any. */
    public RoutingSettings settings(Long orgId) {
        return repository.findByOrganizationId(orgId).orElseGet(() -> {
            RoutingSettings s = new RoutingSettings();
            s.setOrganizationId(orgId);
            return s;
        });
    }

    /** Creates the row on first save, then updates everything but the pointer. */
    public RoutingSettings save(Long orgId, boolean enabled, Long teamId, boolean respectTerritory, Long by) {
        return requiresNew.execute(tx -> {
            if (repository.findByOrganizationId(orgId).isEmpty()) {
                RoutingSettings s = new RoutingSettings();
                s.setOrganizationId(orgId);
                s.setUpdatedAt(Instant.now());
                try {
                    repository.saveAndFlush(s);
                } catch (DataIntegrityViolationException raced) {
                    // a concurrent first save created it — fall through to the update
                }
            }
            repository.updateSettings(orgId, enabled, teamId, respectTerritory, by, Instant.now());
            return repository.findByOrganizationId(orgId).orElseThrow();
        });
    }

    /** Should a lead this actor creates without an owner be routed? */
    public boolean applies(User actor) {
        if (!settings(actor.getOrganizationId()).isEnabled()) return false;
        return isApiRequest() || isManagerUp(actor);
    }

    /** Eligible reps in rotation order (by id) for a lead in {@code geography}. */
    public List<User> eligible(RoutingSettings s, String geography) {
        List<User> pool;
        if (s.getTeamId() != null) {
            List<Long> ids = teamMemberRepository.findByTeamId(s.getTeamId()).stream().map(TeamMember::getUserId).toList();
            pool = userRepository.findAllById(ids);
        } else {
            pool = userRepository.findByOrganizationId(s.getOrganizationId());
        }
        String geo = geography == null ? null : geography.toLowerCase(Locale.ROOT);
        return pool.stream()
                .filter(u -> s.getOrganizationId().equals(u.getOrganizationId()))
                .filter(User::isActive)
                .filter(u -> REP_ROLES.contains(u.getRole()))
                .filter(u -> !s.isRespectTerritory() || u.getRole() != Role.FIELD_SALES
                        || (geo != null && u.getTerritory() != null && !u.getTerritory().isBlank()
                            && geo.contains(u.getTerritory().toLowerCase(Locale.ROOT))))
                .sorted(Comparator.comparing(User::getId))
                .toList();
    }

    /**
     * Takes the next turn in the rotation: the first eligible rep after the pointer
     * (wrapping around), committed atomically. Empty when routing is off or nobody is eligible.
     */
    public Optional<User> nextAssignee(Long orgId, String geography) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Turn turn;
            try {
                turn = requiresNew.execute(tx -> takeTurn(orgId, geography));
            } catch (ConcurrencyFailureException e) {
                turn = Turn.RETRY; // lock timeout / serialization failure: a concurrent turn is in flight
            }
            if (turn == null || turn == Turn.NONE) return Optional.empty();
            if (turn != Turn.RETRY) return Optional.of(turn.user);
            backoff(attempt);
        }
        throw new IllegalStateException("lead routing is busy; please retry");
    }

    private static final class Turn {
        static final Turn NONE = new Turn(null);
        static final Turn RETRY = new Turn(null);
        final User user;
        Turn(User user) { this.user = user; }
    }

    private Turn takeTurn(Long orgId, String geography) {
        RoutingSettings s = repository.findByOrganizationId(orgId).orElse(null);
        if (s == null || !s.isEnabled()) return Turn.NONE;
        List<User> eligible = eligible(s, geography);
        if (eligible.isEmpty()) return Turn.NONE;
        Long last = s.getLastAssignedUserId();
        User next = eligible.stream().filter(u -> last == null || u.getId() > last).findFirst().orElse(eligible.get(0));
        int moved = repository.advance(orgId, s.getVersion(), next.getId());
        return moved == 1 ? new Turn(next) : Turn.RETRY;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(50, 2L * attempt) + (long) (Math.random() * 5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while routing a lead");
        }
    }

    static boolean isManagerUp(User u) {
        return u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.VP_SALES || u.getRole() == Role.MANAGER;
    }

    static boolean isApiRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && attrs.getRequest().getAttribute(ApiKeyAuthFilter.ATTR_API_KEY) != null;
    }
}
