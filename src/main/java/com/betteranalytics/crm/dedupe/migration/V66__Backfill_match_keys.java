package com.betteranalytics.crm.dedupe.migration;

import com.betteranalytics.crm.dedupe.MatchKeys;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * V66 — fills the V65 match-key columns for every existing lead, contact and
 * company (soft-deleted rows included, so restoring one keeps it matchable).
 * A Java migration so the keys are computed by exactly the same code
 * ({@link MatchKeys}) that maintains them at runtime — portable across H2 and
 * PostgreSQL, no regex dialect differences. Runs in id-ordered batches.
 * Spring Boot hands JavaMigration beans to Flyway (see V50).
 */
@Component
public class V66__Backfill_match_keys extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V66__Backfill_match_keys.class);
    private static final int BATCH = 500;

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        int n = backfill(c, "leads", false) + backfill(c, "contacts", false) + backfill(c, "companies", true);
        if (n > 0) log.info("V66: computed duplicate-match keys for {} record(s)", n);
    }

    public static int backfill(Connection c, String table, boolean company) throws SQLException {
        String select = "SELECT id, name, email, phone" + (company ? ", website" : "")
                + " FROM " + table + " WHERE id > ? ORDER BY id";
        String update = "UPDATE " + table + " SET email_normalized = ?, phone_normalized = ?, domain_normalized = ?,"
                + " name_key = ?, name_phonetic = ? WHERE id = ?";
        int total = 0;
        long lastId = 0;
        try (PreparedStatement sel = c.prepareStatement(select); PreparedStatement upd = c.prepareStatement(update)) {
            sel.setMaxRows(BATCH);
            while (true) {
                sel.setLong(1, lastId);
                int inBatch = 0;
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) {
                        lastId = rs.getLong("id");
                        String name = rs.getString("name");
                        String email = rs.getString("email");
                        String phone = rs.getString("phone");
                        String nameKey = company ? MatchKeys.companyKey(name) : MatchKeys.nameKey(name);
                        String domain = company ? MatchKeys.websiteDomain(rs.getString("website")) : null;
                        if (domain == null) domain = MatchKeys.emailDomain(email);
                        upd.setString(1, MatchKeys.email(email));
                        upd.setString(2, MatchKeys.phone(phone));
                        upd.setString(3, domain);
                        upd.setString(4, nameKey);
                        upd.setString(5, company ? MatchKeys.companyPhonetic(nameKey) : MatchKeys.personPhonetic(nameKey));
                        upd.setLong(6, lastId);
                        upd.addBatch();
                        inBatch++;
                    }
                }
                if (inBatch == 0) break;
                upd.executeBatch();
                total += inBatch;
                if (inBatch < BATCH) break;
            }
        }
        return total;
    }
}
