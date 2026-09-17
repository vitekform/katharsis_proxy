package me.ganamaga.katharsis_proxy.link;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Generates and reads link codes.
 *
 * <p>Codes are {@value #LENGTH} characters from an alphabet without look-alikes
 * (no {@code 0/O}, {@code 1/I/L}), so they survive being read off one screen
 * and typed into another. That is 40 bits: guessing one is hopeless while the
 * in-game command is rate limited.</p>
 */
public final class LinkCodes {

    public static final int LENGTH = 8;

    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final RandomGenerator random;

    public LinkCodes() {
        this(new SecureRandom());
    }

    LinkCodes(RandomGenerator random) {
        this.random = random;
    }

    /** A fresh random code. */
    public String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * Reads what a player typed: case, spaces and dashes are ignored.
     *
     * @return the code, or empty if it cannot be one this class generated
     */
    public static Optional<String> normalise(String input) {
        String code = input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (code.length() != LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < code.length(); i++) {
            if (ALPHABET.indexOf(code.charAt(i)) < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(code);
    }
}
