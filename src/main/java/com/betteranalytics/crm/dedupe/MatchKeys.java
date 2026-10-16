package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.entity.Company;
import com.betteranalytics.crm.entity.Contact;
import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.service.PhoneNumbers;
import org.apache.commons.codec.language.DoubleMetaphone;

import java.net.URI;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalized match keys stored (and indexed) on leads, contacts and companies,
 * so duplicate candidates are found with equality lookups instead of table scans:
 * <ul>
 *   <li>email_normalized — lower-cased, trimmed email;</li>
 *   <li>phone_normalized — E.164 via {@link PhoneNumbers#tryNormalize}, else the bare digits;</li>
 *   <li>domain_normalized — company web domain from the website or a non-freemail email, without "www.";</li>
 *   <li>name_key — lower-case, accents and punctuation stripped, single-spaced
 *       (company legal suffixes such as "Pvt Ltd" / "Inc" removed);</li>
 *   <li>name_phonetic — Double Metaphone code of the surname (people) or the first
 *       significant word (companies): the blocking key for fuzzy-name matching.</li>
 * </ul>
 * The same functions run in the entity lifecycle callbacks and in the V66 backfill.
 */
public final class MatchKeys {

    private MatchKeys() {}

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");
    private static final DoubleMetaphone METAPHONE = new DoubleMetaphone();

    /** Freemail providers: their domain says nothing about the company someone works for. */
    static final Set<String> FREEMAIL = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "yahoo.in", "ymail.com", "rediffmail.com",
            "hotmail.com", "outlook.com", "live.com", "msn.com", "icloud.com", "me.com", "mac.com", "aol.com",
            "protonmail.com", "proton.me", "zoho.com", "zohomail.com", "gmx.com", "gmx.de", "mail.com",
            "yandex.com", "yandex.ru", "qq.com", "163.com");

    /** Words dropped from company names before comparing ("Acme Pvt. Ltd." == "ACME"). */
    static final Set<String> COMPANY_SUFFIXES = Set.of(
            "pvt", "private", "ltd", "limited", "inc", "incorporated", "llc", "llp", "plc", "corp",
            "corporation", "co", "company", "gmbh", "ag", "sa", "bv", "pty", "the", "and");

    public static String email(String raw) {
        if (raw == null) return null;
        String e = raw.trim().toLowerCase(Locale.ROOT);
        int at = e.indexOf('@');
        if (at <= 0 || at == e.length() - 1) return null;
        return truncate(e, 255);
    }

    /** E.164 ("+919876543210") when plausible, otherwise the digits (min. 6) — null when there is no number. */
    public static String phone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String e164 = PhoneNumbers.tryNormalize(raw);
        if (e164 != null) return e164;
        String digits = NON_DIGIT.matcher(raw).replaceAll("");
        if (digits.length() < 6) return null;
        return truncate(digits, 32);
    }

    /** Domain of a website URL ("https://www.Acme.com/about" → "acme.com"). */
    public static String websiteDomain(String website) {
        if (website == null || website.isBlank()) return null;
        String w = website.trim().toLowerCase(Locale.ROOT);
        if (w.startsWith("mailto:")) return emailDomain(w.substring(7));
        if (!w.contains("://")) w = "http://" + w;
        String host;
        try {
            host = URI.create(w).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (host == null) {
            String s = w.substring(w.indexOf("://") + 3);
            int cut = s.length();
            for (char c : new char[]{'/', '?', '#', ':'}) {
                int i = s.indexOf(c);
                if (i >= 0) cut = Math.min(cut, i);
            }
            host = s.substring(0, cut);
        }
        return cleanDomain(host);
    }

    /** Company domain of an email address; null for freemail providers. */
    public static String emailDomain(String email) {
        String e = email(email);
        if (e == null) return null;
        return cleanDomain(e.substring(e.indexOf('@') + 1));
    }

    private static String cleanDomain(String host) {
        if (host == null) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.startsWith("www.")) h = h.substring(4);
        if (h.isEmpty() || !h.contains(".") || FREEMAIL.contains(h)) return null;
        return truncate(h, 255);
    }

    /** Lower-case, accent-free, punctuation-free, single-spaced. */
    public static String nameKey(String raw) {
        if (raw == null) return null;
        String n = Normalizer.normalize(raw, Normalizer.Form.NFKD);
        n = DIACRITICS.matcher(n).replaceAll("");
        n = NON_ALNUM.matcher(n.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        return n.isEmpty() ? null : truncate(n, 255);
    }

    /** {@link #nameKey} without legal suffixes / filler words. */
    public static String companyKey(String raw) {
        String k = nameKey(raw);
        if (k == null) return null;
        List<String> words = Arrays.stream(k.split(" ")).filter(w -> !COMPANY_SUFFIXES.contains(w)).toList();
        String out = String.join(" ", words);
        return out.isEmpty() ? k : out;
    }

    /** Double Metaphone of the surname (last word); a lone initial is not a surname. */
    public static String personPhonetic(String nameKey) {
        if (nameKey == null) return null;
        String[] t = nameKey.split(" ");
        return phonetic(t[t.length - 1]);
    }

    /** Double Metaphone of the first word of a company key. */
    public static String companyPhonetic(String companyKey) {
        if (companyKey == null) return null;
        return phonetic(companyKey.split(" ")[0]);
    }

    static String phonetic(String word) {
        if (word == null || word.isBlank()) return null;
        String code = METAPHONE.doubleMetaphone(word);
        if (code == null || code.isEmpty()) code = word; // digits / non-latin script: the word itself
        return truncate(code, 16);
    }

    // ── entity maintenance (called from @PrePersist / @PreUpdate and the backfill) ──

    public static void refresh(Lead l) {
        l.setEmailNormalized(email(l.getEmail()));
        l.setPhoneNormalized(phone(l.getPhone()));
        l.setDomainNormalized(emailDomain(l.getEmail()));
        l.setNameKey(nameKey(l.getName()));
        l.setNamePhonetic(personPhonetic(l.getNameKey()));
    }

    public static void refresh(Contact c) {
        c.setEmailNormalized(email(c.getEmail()));
        c.setPhoneNormalized(phone(c.getPhone()));
        c.setDomainNormalized(emailDomain(c.getEmail()));
        c.setNameKey(nameKey(c.getName()));
        c.setNamePhonetic(personPhonetic(c.getNameKey()));
    }

    public static void refresh(Company c) {
        c.setEmailNormalized(email(c.getEmail()));
        c.setPhoneNormalized(phone(c.getPhone()));
        String d = websiteDomain(c.getWebsite());
        c.setDomainNormalized(d != null ? d : emailDomain(c.getEmail()));
        c.setNameKey(companyKey(c.getName()));
        c.setNamePhonetic(companyPhonetic(c.getNameKey()));
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
