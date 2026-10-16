package com.betteranalytics.crm.dedupe;

import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

/**
 * Fuzzy name comparison on {@link MatchKeys#nameKey name keys}, modelled on the
 * Salesforce standard contact/lead matching rule: exact, Jaro-Winkler (≥ 0.92),
 * phonetic (Double Metaphone, primary or alternate code) and name variants with
 * initials ("J Smith" ~ "John Smith"). Returns a similarity in [0, 1], 0 = no match.
 */
public final class NameMatcher {

    public static final double JARO_WINKLER_THRESHOLD = 0.92;
    static final double PHONETIC_SCORE = 0.9;
    static final double INITIALS_SCORE = 0.88;

    private static final JaroWinklerSimilarity JW = new JaroWinklerSimilarity();
    private static final DoubleMetaphone METAPHONE = new DoubleMetaphone();

    private NameMatcher() {}

    public static double similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1.0;
        // "Branch 12" and "Branch 13" are different records, however similar the strings
        if (!digits(a).equals(digits(b))) return 0;
        double jw = JW.apply(a, b);
        if (jw >= JARO_WINKLER_THRESHOLD) return jw;

        String[] ta = a.split(" ");
        String[] tb = b.split(" ");
        if (ta.length == tb.length && allPhoneticallyEqual(ta, tb)) return PHONETIC_SCORE;
        if (initialsVariant(ta, tb) || initialsVariant(tb, ta)) return INITIALS_SCORE;
        return 0;
    }

    private static String digits(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) b.append(s.charAt(i));
        }
        return b.toString();
    }

    private static boolean allPhoneticallyEqual(String[] a, String[] b) {
        for (int i = 0; i < a.length; i++) {
            if (!soundsAlike(a[i], b[i])) return false;
        }
        return true;
    }

    static boolean soundsAlike(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() < 2 || b.length() < 2) return false; // an initial is not a phonetic match
        return METAPHONE.isDoubleMetaphoneEqual(a, b, false) || METAPHONE.isDoubleMetaphoneEqual(a, b, true);
    }

    /**
     * {@code shortForm} abbreviates {@code full}: same surname (exact or phonetic), and
     * every other word either equal or an initial of the corresponding word —
     * "j smith" / "john smith", "j r smith" / "john robert smith". At least one initial
     * must be involved, otherwise it is not a variant but a different name.
     */
    private static boolean initialsVariant(String[] shortForm, String[] full) {
        if (shortForm.length != full.length || shortForm.length < 2) return false;
        int last = shortForm.length - 1;
        if (!soundsAlike(shortForm[last], full[last])) return false;
        boolean usedInitial = false;
        for (int i = 0; i < last; i++) {
            String s = shortForm[i];
            String f = full[i];
            if (s.equals(f)) continue;
            if (s.length() == 1 && f.charAt(0) == s.charAt(0)) {
                usedInitial = true;
                continue;
            }
            return false;
        }
        return usedInitial;
    }
}
