package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.core.CrmMasks;
import com.betteranalytics.crm.core.Specs;
import com.betteranalytics.crm.entity.Company;
import com.betteranalytics.crm.entity.Contact;
import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import com.betteranalytics.crm.repository.CompanyRepository;
import com.betteranalytics.crm.repository.ContactRepository;
import com.betteranalytics.crm.repository.LeadRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * The matching rules. Candidates are pre-filtered in SQL by equality on the
 * indexed normalized keys (email, phone, domain, name key, surname/first-word
 * phonetic code) — never a whole-table scan — and then scored in Java:
 * <ul>
 *   <li>exact email (1.0) or phone (0.95);</li>
 *   <li>companies: exact web domain (0.97) or fuzzy name;</li>
 *   <li>people: fuzzy name ({@link NameMatcher}: Jaro-Winkler ≥ 0.92, Double Metaphone,
 *       initials), full weight when the same company / company domain backs it up,
 *       0.85× on the name alone. A shared company domain alone is not a duplicate.</li>
 * </ul>
 */
@Service
public class DuplicateDetector {

    /** Most rows one candidate lookup reads. */
    static final int CANDIDATE_LIMIT = 200;
    /** Candidates returned to a create/edit caller. */
    static final int RESULT_LIMIT = 10;
    /** Duplicate review: most key values (blocks) per key, and rows per block. */
    static final int MAX_BLOCKS_PER_KEY = 500;
    static final int MAX_BLOCK_SIZE = 100;

    private final LeadRepository leadRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final PermissionService permissionService;
    private final CrmMasks masks;
    private final EntityManager em;

    public DuplicateDetector(LeadRepository leadRepository, ContactRepository contactRepository,
                             CompanyRepository companyRepository, PermissionService permissionService,
                             CrmMasks masks, EntityManager em) {
        this.leadRepository = leadRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.permissionService = permissionService;
        this.masks = masks;
        this.em = em;
    }

    /** How the detector reads one entity type. */
    private record Kind<T>(ObjectType type, Class<T> cls, JpaSpecificationExecutor<T> repo,
                           Function<T, Probe> probe, Function<T, Long> id, Function<T, String> name,
                           Function<T, Long> owner, Function<T, Instant> createdAt) {}

    private Kind<?> kind(ObjectType type) {
        return switch (type) {
            case LEAD -> new Kind<>(type, Lead.class, leadRepository, Probe::of, Lead::getId, Lead::getName,
                    Lead::getOwnerId, Lead::getCreatedAt);
            case CONTACT -> new Kind<>(type, Contact.class, contactRepository, Probe::of, Contact::getId,
                    Contact::getName, Contact::getOwnerId, Contact::getCreatedAt);
            case COMPANY -> new Kind<>(type, Company.class, companyRepository, Probe::of, Company::getId,
                    Company::getName, Company::getOwnerId, Company::getCreatedAt);
        };
    }

    // ── scoring ─────────────────────────────────────────────────────────────────

    record Match(double score, List<String> matchedOn) {}

    /** Null when the two records do not match. */
    static Match score(ObjectType type, Probe a, Probe b) {
        List<String> on = new ArrayList<>();
        double s = 0;
        if (a.email() != null && a.email().equals(b.email())) { on.add("email"); s = Math.max(s, 1.0); }
        if (a.phone() != null && a.phone().equals(b.phone())) { on.add("phone"); s = Math.max(s, 0.95); }
        boolean sameDomain = a.domain() != null && a.domain().equals(b.domain());
        double name = NameMatcher.similarity(a.nameKey(), b.nameKey());
        if (type == ObjectType.COMPANY) {
            if (sameDomain) { on.add("domain"); s = Math.max(s, 0.97); }
            if (name > 0) { on.add("name"); s = Math.max(s, name); }
        } else if (name > 0) {
            boolean sameCompany = (a.companyId() != null && a.companyId().equals(b.companyId()))
                    || (a.companyKey() != null && a.companyKey().equals(b.companyKey()));
            on.add("name");
            if (sameDomain) on.add("domain");
            if (sameCompany) on.add("company");
            s = Math.max(s, (sameDomain || sameCompany) ? name : name * 0.85);
        }
        if (on.isEmpty()) return null;
        return new Match(Math.round(s * 1000) / 1000.0, List.copyOf(on));
    }

    // ── candidates for one record (create / edit checks) ────────────────────────

    /**
     * Records matching {@code probe}, best first. {@code visibleOnly} restricts them to
     * what the actor may VIEW (in SQL); otherwise the whole organization is searched.
     */
    @Transactional(readOnly = true)
    public List<DuplicateCandidate> find(User actor, ObjectType type, Probe probe, boolean visibleOnly) {
        return findTyped(kind(type), actor, probe, visibleOnly);
    }

    private <T> List<DuplicateCandidate> findTyped(Kind<T> k, User actor, Probe p, boolean visibleOnly) {
        Specification<T> blocks = (root, q, cb) -> {
            List<Predicate> any = new ArrayList<>();
            if (p.email() != null) any.add(cb.equal(root.get("emailNormalized"), p.email()));
            if (p.phone() != null) any.add(cb.equal(root.get("phoneNormalized"), p.phone()));
            if (p.domain() != null && k.type() == ObjectType.COMPANY) any.add(cb.equal(root.get("domainNormalized"), p.domain()));
            if (p.nameKey() != null) any.add(cb.equal(root.get("nameKey"), p.nameKey()));
            if (p.phonetic() != null) any.add(cb.equal(root.get("namePhonetic"), p.phonetic()));
            return any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new));
        };
        Specification<T> spec = Specs.<T>org(actor.getOrganizationId()).and(blocks);
        if (p.selfId() != null) spec = spec.and((root, q, cb) -> cb.notEqual(root.get("id"), p.selfId()));
        if (visibleOnly) {
            Specification<T> scope = scope(actor, k.type());
            if (scope == null) return List.of();
            spec = spec.and(scope);
        }
        List<T> rows = k.repo().findAll(spec, PageRequest.of(0, CANDIDATE_LIMIT, Sort.by(Sort.Direction.DESC, "id")))
                .getContent();
        List<DuplicateCandidate> out = new ArrayList<>();
        for (T row : rows) {
            Match m = score(k.type(), p, k.probe().apply(row));
            if (m != null) out.add(new DuplicateCandidate(k.id().apply(row), k.name().apply(row), m.matchedOn(),
                    m.score(), k.owner().apply(row)));
        }
        out.sort(Comparator.comparingDouble(DuplicateCandidate::score).reversed()
                .thenComparing(DuplicateCandidate::id, Comparator.reverseOrder()));
        return out.size() > RESULT_LIMIT ? List.copyOf(out.subList(0, RESULT_LIMIT)) : out;
    }

    /** May the actor VIEW this candidate? */
    public boolean visible(User actor, ObjectType type, DuplicateCandidate c) {
        return permissionService.can(actor, Action.VIEW, type.resource, actor.getOrganizationId(), c.ownerId());
    }

    private <T> Specification<T> scope(User actor, ObjectType type) {
        OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, type.resource);
        if (scope.none()) return null;
        return Specs.ownedWithin(scope, "ownerId");
    }

    // ── duplicate review: groups across the visible records ─────────────────────

    /**
     * Candidate groups among the records the actor may VIEW. Computed inside blocks:
     * SQL finds key values shared by 2+ visible records (GROUP BY ... HAVING COUNT > 1
     * on email, phone, phonetic name code and — for companies — domain); only those
     * blocks are loaded and compared pairwise; matches are merged into groups
     * (union-find). Bounded by {@link #MAX_BLOCKS_PER_KEY} / {@link #MAX_BLOCK_SIZE}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> groups(User actor, ObjectType type, int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page must be 0 or greater");
        int s = Math.max(1, Math.min(size, 100));
        return groupsTyped(kind(type), actor, page, s);
    }

    private <T> Map<String, Object> groupsTyped(Kind<T> k, User actor, int page, int size) {
        Specification<T> scope = scope(actor, k.type());
        if (scope == null) {
            throw new AccessDeniedException("not permitted: " + actor.getRole() + " cannot VIEW " + k.type().plural);
        }
        Specification<T> visible = Specs.<T>org(actor.getOrganizationId()).and(scope);
        List<String> keys = new ArrayList<>(List.of("emailNormalized", "phoneNormalized", "namePhonetic"));
        if (k.type() == ObjectType.COMPANY) keys.add("domainNormalized");

        Map<Long, T> byId = new HashMap<>();
        List<List<Long>> blocks = new ArrayList<>();
        boolean truncated = false;
        for (String attr : keys) {
            List<String> shared = sharedValues(k.cls(), visible, attr);
            if (shared.size() >= MAX_BLOCKS_PER_KEY) truncated = true;
            for (int i = 0; i < shared.size(); i += 100) {
                List<String> chunk = shared.subList(i, Math.min(shared.size(), i + 100));
                Specification<T> spec = visible.and((root, q, cb) -> root.get(attr).in(chunk));
                List<T> rows = k.repo().findAll(spec, Sort.by("id"));
                Map<String, List<Long>> perValue = new LinkedHashMap<>();
                for (T row : rows) {
                    Long id = k.id().apply(row);
                    byId.putIfAbsent(id, row);
                    String v = valueOf(k.probe().apply(row), attr);
                    if (v == null) continue;
                    List<Long> members = perValue.computeIfAbsent(v, x -> new ArrayList<>());
                    if (members.size() < MAX_BLOCK_SIZE) members.add(id);
                    else truncated = true;
                }
                blocks.addAll(perValue.values());
            }
        }

        // pairwise comparison inside each block, unioned into groups
        Map<Long, Probe> probes = new HashMap<>();
        byId.forEach((id, row) -> probes.put(id, k.probe().apply(row)));
        UnionFind uf = new UnionFind();
        Map<Long, Double> bestScore = new HashMap<>();
        Map<Long, Set<String>> matchedOn = new HashMap<>();
        Set<List<Long>> compared = new HashSet<>();
        for (List<Long> block : blocks) {
            for (int i = 0; i < block.size(); i++) {
                for (int j = i + 1; j < block.size(); j++) {
                    long a = block.get(i), b = block.get(j);
                    if (!compared.add(List.of(Math.min(a, b), Math.max(a, b)))) continue;
                    Match m = score(k.type(), probes.get(a), probes.get(b));
                    if (m == null) continue;
                    uf.union(a, b);
                    bestScore.merge(a, m.score(), Math::max);
                    bestScore.merge(b, m.score(), Math::max);
                    matchedOn.computeIfAbsent(a, x -> new TreeSet<>()).addAll(m.matchedOn());
                    matchedOn.computeIfAbsent(b, x -> new TreeSet<>()).addAll(m.matchedOn());
                }
            }
        }

        Map<Long, List<Long>> components = new HashMap<>();
        for (Long id : bestScore.keySet()) components.computeIfAbsent(uf.find(id), x -> new ArrayList<>()).add(id);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (List<Long> members : components.values()) {
            if (members.size() < 2) continue;
            List<T> rows = members.stream().map(byId::get)
                    .sorted(Comparator.comparing((T r) -> k.createdAt().apply(r), Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(r -> k.id().apply(r)))
                    .toList();
            double score = members.stream().mapToDouble(bestScore::get).max().orElse(0);
            Set<String> on = new TreeSet<>();
            members.forEach(id -> on.addAll(matchedOn.getOrDefault(id, Set.of())));
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("key", k.type().name().toLowerCase(Locale.ROOT) + ":" + k.id().apply(rows.get(0)));
            g.put("score", score);
            g.put("matchedOn", List.copyOf(on));
            g.put("records", rows.stream().map(r -> present(actor, r)).toList());
            g.put("latestId", members.stream().mapToLong(Long::longValue).max().orElse(0));
            groups.add(g);
        }
        groups.sort(Comparator.comparingDouble((Map<String, Object> g) -> (Double) g.get("score")).reversed()
                .thenComparing(g -> (Long) g.get("latestId"), Comparator.reverseOrder()));
        groups.forEach(g -> g.remove("latestId"));

        int from = Math.min(groups.size(), page * size);
        int to = Math.min(groups.size(), from + size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", groups.size());
        out.put("data", groups.subList(from, to));
        out.put("page", page);
        out.put("size", size);
        out.put("truncated", truncated);
        return out;
    }

    /** Leads go through the contact-info mask; contacts and companies are returned as stored. */
    private Object present(User actor, Object row) {
        return row instanceof Lead l ? masks.lead(actor, l) : row;
    }

    private static String valueOf(Probe p, String attr) {
        return switch (attr) {
            case "emailNormalized" -> p.email();
            case "phoneNormalized" -> p.phone();
            case "domainNormalized" -> p.domain();
            default -> p.phonetic();
        };
    }

    /** SELECT attr FROM T WHERE visible AND attr IS NOT NULL GROUP BY attr HAVING COUNT(*) > 1. */
    private <T> List<String> sharedValues(Class<T> cls, Specification<T> visible, String attr) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<String> q = cb.createQuery(String.class);
        Root<T> root = q.from(cls);
        Predicate where = visible.toPredicate(root, q, cb);
        q.select(root.get(attr))
                .where(cb.and(where, cb.isNotNull(root.get(attr))))
                .groupBy(root.get(attr))
                .having(cb.gt(cb.count(root), 1L))
                .orderBy(cb.asc(root.get(attr)));
        return em.createQuery(q).setMaxResults(MAX_BLOCKS_PER_KEY).getResultList();
    }

    private static final class UnionFind {
        private final Map<Long, Long> parent = new HashMap<>();

        long find(long x) {
            Long p = parent.get(x);
            if (p == null) { parent.put(x, x); return x; }
            if (p == x) return x;
            long root = find(p);
            parent.put(x, root);
            return root;
        }

        void union(long a, long b) {
            long ra = find(a), rb = find(b);
            if (ra != rb) parent.put(Math.max(ra, rb), Math.min(ra, rb));
        }
    }
}
