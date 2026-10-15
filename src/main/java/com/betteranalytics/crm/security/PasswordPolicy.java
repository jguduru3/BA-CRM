package com.betteranalytics.crm.security;

import java.nio.charset.StandardCharsets;

/**
 * One password rule set for every place a password is set (user create,
 * admin reset, self change). BCrypt only hashes the first 72 bytes, so
 * anything longer is rejected rather than silently truncated.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_BYTES  = 72;

    private PasswordPolicy() {}

    /** @return null when acceptable, otherwise a user-facing reason. */
    public static String validate(String password, String email) {
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
        if (email != null && !email.isBlank()) {
            String local = email.split("@")[0].toLowerCase();
            if (local.length() >= 4 && password.toLowerCase().contains(local)) {
                return "password must not contain your email name";
            }
        }
        return null;
    }
}
