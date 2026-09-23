package me.ganamaga.katharsis_proxy.auth;

import java.util.Locale;
import java.util.Optional;

/**
 * What a new password must be. Checked before anything is hashed, so a refused
 * password costs nothing.
 *
 * @param minLength shortest allowed, in characters
 * @param maxLength longest allowed, in characters
 */
public record PasswordPolicy(int minLength, int maxLength) {

    /** Why a password was refused. */
    public enum Problem {
        TOO_SHORT,
        TOO_LONG,
        /** The confirmation was typed differently. */
        MISMATCH,
        /** The password is the player's own name, which anyone can read. */
        IS_USERNAME
    }

    /** The first problem with {@code password}, or empty if it may be used. */
    public Optional<Problem> check(String username, String password, String confirmation) {
        if (!password.equals(confirmation)) {
            return Optional.of(Problem.MISMATCH);
        }
        int length = password.codePointCount(0, password.length());
        if (length < minLength) {
            return Optional.of(Problem.TOO_SHORT);
        }
        if (length > maxLength) {
            return Optional.of(Problem.TOO_LONG);
        }
        if (password.toLowerCase(Locale.ROOT).equals(username.toLowerCase(Locale.ROOT))) {
            return Optional.of(Problem.IS_USERNAME);
        }
        return Optional.empty();
    }
}
