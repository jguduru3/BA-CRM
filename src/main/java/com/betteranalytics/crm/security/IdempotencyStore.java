package com.betteranalytics.crm.security;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The idempotency_keys table. Every statement auto-commits on its own (no
 * surrounding transaction), so an IN_PROGRESS row is visible to concurrent
 * retries before the request it guards starts executing, and the unique
 * (credential_key, idem_key) constraint decides which of two racing requests wins.
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    /** Keys are kept (and honoured) for 24 hours, then treated as new. */
    public static final Duration TTL = Duration.ofHours(24);

    public enum Kind { NEW, REPLAY, MISMATCH, IN_PROGRESS }

    public record Stored(int status, String contentType, String body) {}

    public record Begin(Kind kind, Stored stored) {}

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Row(long id, String hash, String status, Integer responseStatus, String contentType,
                       String body, Instant createdAt) {}

    /** Claims the key for this request, or tells the caller what to answer instead. */
    public Begin begin(String credential, String key, String hash) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Instant now = Instant.now();
            try {
                jdbc.update("INSERT INTO idempotency_keys (credential_key, idem_key, request_hash, status, created_at) " +
                            "VALUES (?, ?, ?, ?, ?)", credential, key, hash, IN_PROGRESS, utc(now));
                return new Begin(Kind.NEW, null);
            } catch (DataIntegrityViolationException dup) {
                Row row = find(credential, key);
                if (row == null) return new Begin(Kind.IN_PROGRESS, null);   // released by a racing request
                if (row.createdAt.isBefore(now.minus(TTL))) {
                    // Expired but not yet pruned: the key is new again.
                    jdbc.update("DELETE FROM idempotency_keys WHERE id = ?", row.id);
                    continue;
                }
                if (!row.hash.equals(hash)) return new Begin(Kind.MISMATCH, null);
                if (IN_PROGRESS.equals(row.status) || row.responseStatus == null) return new Begin(Kind.IN_PROGRESS, null);
                return new Begin(Kind.REPLAY, new Stored(row.responseStatus, row.contentType, row.body));
            }
        }
        return new Begin(Kind.IN_PROGRESS, null);
    }

    public void complete(String credential, String key, int status, String contentType, String body) {
        jdbc.update("UPDATE idempotency_keys SET status = ?, response_status = ?, content_type = ?, response_body = ?, " +
                    "completed_at = ? WHERE credential_key = ? AND idem_key = ? AND status = ?",
                COMPLETED, status, contentType, body, utc(Instant.now()), credential, key, IN_PROGRESS);
    }

    /** Forgets an in-progress key whose result must not be stored, so the client may retry with it. */
    public void release(String credential, String key) {
        jdbc.update("DELETE FROM idempotency_keys WHERE credential_key = ? AND idem_key = ? AND status = ?",
                credential, key, IN_PROGRESS);
    }

    public int prune(Instant olderThan) {
        return jdbc.update("DELETE FROM idempotency_keys WHERE created_at < ?", utc(olderThan));
    }

    /** Hourly, one instance cluster-wide: drop keys older than 24 hours. */
    @Scheduled(fixedDelayString = "${app.idempotency.prune-delay-ms:3600000}",
               initialDelayString = "${app.idempotency.prune-initial-delay-ms:300000}")
    @SchedulerLock(name = "idempotency.prune", lockAtMostFor = "PT10M")
    public void pruneExpired() {
        try {
            int n = prune(Instant.now().minus(TTL));
            if (n > 0) log.info("Pruned {} expired idempotency keys", n);
        } catch (Exception e) {
            log.warn("Idempotency key pruning failed: {}", e.toString());
        }
    }

    /** TIMESTAMP columns hold UTC wall-clock time (same convention as hibernate.jdbc.time_zone=UTC). */
    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private Row find(String credential, String key) {
        List<Row> rows = jdbc.query(
                "SELECT id, request_hash, status, response_status, content_type, response_body, created_at " +
                "FROM idempotency_keys WHERE credential_key = ? AND idem_key = ?",
                (rs, i) -> {
                    int st = rs.getInt("response_status");
                    Integer status = rs.wasNull() ? null : st;
                    return new Row(rs.getLong("id"), rs.getString("request_hash"), rs.getString("status"), status,
                            rs.getString("content_type"), rs.getString("response_body"),
                            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
                },
                credential, key);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
