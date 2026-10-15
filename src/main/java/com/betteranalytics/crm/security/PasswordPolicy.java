package com.betteranalytics.crm.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * One password rule set for every place a password is set (user create,
 * admin reset, self change, first-admin bootstrap). BCrypt only hashes the
 * first 72 bytes, so anything longer is rejected rather than silently truncated.
 *
 * Besides length and character classes, a password is rejected when it is (or
 * is a common decoration of — "Password2024!", "Welcome@123") one of the ~1,000
 * most common passwords shipped in {@code security/common-passwords.txt}, or
 * when it contains the user's own name or email name. Comparisons are
 * case-insensitive.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_BYTES  = 72;

    private static final String LIST = "/security/common-passwords.txt";
    private static final Set<String> COMMON = load();

    private PasswordPolicy() {}

    /** @return null when acceptable, otherwise a user-facing reason. */
    public static String validate(String password, String email) {
        return validate(password, email, null);
    }

    /** @return null when acceptable, otherwise a user-facing reason. */
    public static String validate(String password, String email, String name) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "password must be at least " + MIN_LENGTH + " characters";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "password must be at most " + MAX_BYTES + " bytes";
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit  = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            return "password must contain at least one letter and one number";
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (isCommon(lower)) {
            return "this password is too common; choose something less predictable";
        }
        if (email != null && !email.isBlank()) {
            String local = email.split("@")[0].toLowerCase(Locale.ROOT);
            if (local.length() >= 4 && lower.contains(local)) {
                return "password must not contain your email name";
            }
        }
        if (name != null && !name.isBlank()) {
            for (String part : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (part.length() >= 3 && lower.contains(part)) {
                    return "password must not contain your name";
                }
            }
        }
        return null;
    }

    /** Exact match, or a listed password with only digits/symbols added around it. */
    static boolean isCommon(String lower) {
        if (COMMON.contains(lower)) return true;
        String core = lower.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
        return core.length() >= 4 && COMMON.contains(core);
    }

    static int commonListSize() {
        return COMMON.size();
    }

    private static Set<String> load() {
        Set<String> out = new HashSet<>();
        try (InputStream in = PasswordPolicy.class.getResourceAsStream(LIST)) {
            if (in == null) throw new IllegalStateException("missing " + LIST);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String v = line.trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty() && !v.startsWith("#")) out.add(v);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + LIST, e);
        }
        return Set.copyOf(out);
    }
}
