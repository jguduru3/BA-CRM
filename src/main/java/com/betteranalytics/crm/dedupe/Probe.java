package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.entity.Company;
import com.betteranalytics.crm.entity.Contact;
import com.betteranalytics.crm.entity.Lead;

/**
 * The match keys of one record, computed from its current field values (not the
 * stored columns, which are only refreshed at flush) — what the matching rules compare.
 *
 * @param companyId  contact's company (same company strengthens a name match)
 * @param companyKey lead's business name as a company key (same purpose)
 */
public record Probe(Long selfId, String email, String phone, String domain, String nameKey, String phonetic,
                    Long companyId, String companyKey) {

    public static Probe of(Lead l) {
        String nk = MatchKeys.nameKey(l.getName());
        return new Probe(l.getId(), MatchKeys.email(l.getEmail()), MatchKeys.phone(l.getPhone()),
                MatchKeys.emailDomain(l.getEmail()), nk, MatchKeys.personPhonetic(nk),
                l.getCompanyId(), MatchKeys.companyKey(l.getBusinessName()));
    }

    public static Probe of(Contact c) {
        String nk = MatchKeys.nameKey(c.getName());
        return new Probe(c.getId(), MatchKeys.email(c.getEmail()), MatchKeys.phone(c.getPhone()),
                MatchKeys.emailDomain(c.getEmail()), nk, MatchKeys.personPhonetic(nk), c.getCompanyId(), null);
    }

    public static Probe of(Company c) {
        String nk = MatchKeys.companyKey(c.getName());
        String d = MatchKeys.websiteDomain(c.getWebsite());
        return new Probe(c.getId(), MatchKeys.email(c.getEmail()), MatchKeys.phone(c.getPhone()),
                d != null ? d : MatchKeys.emailDomain(c.getEmail()), nk, MatchKeys.companyPhonetic(nk), null, null);
    }

    /** True when the keys the matching rules look at differ (an edit that needs a duplicate check). */
    public boolean keysDifferFrom(Probe o) {
        return !java.util.Objects.equals(email, o.email) || !java.util.Objects.equals(phone, o.phone)
                || !java.util.Objects.equals(domain, o.domain) || !java.util.Objects.equals(nameKey, o.nameKey)
                || !java.util.Objects.equals(companyId, o.companyId) || !java.util.Objects.equals(companyKey, o.companyKey);
    }
}
