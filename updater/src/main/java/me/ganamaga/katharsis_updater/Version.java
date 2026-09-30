package me.ganamaga.katharsis_updater;

import java.util.ArrayList;
import java.util.List;

/**
 * A Katharsis version as the release system names it: {@code 0.2} for a
 * tagged release, {@code 0.2-dev.41} for a build of master.
 *
 * <p>Ordered like semantic versions: the dot-separated numbers before the
 * first {@code -} compare numerically, and a version with a suffix comes
 * before the same version without one, so {@code 0.1-dev.13 < 0.1 <
 * 0.2-dev.14 < 0.2}. Suffix parts compare numerically when both are numbers
 * ({@code dev.9 < dev.10}), as text otherwise.</p>
 *
 * <p>Anything is accepted; parts that are not numbers compare as text, so
 * an odd version still sorts somewhere stable rather than throwing.</p>
 */
public final class Version implements Comparable<Version> {

    private final String text;
    private final List<String> release;
    private final List<String> suffix;

    private Version(String text, List<String> release, List<String> suffix) {
        this.text = text;
        this.release = release;
        this.suffix = suffix;
    }

    public static Version parse(String text) {
        String trimmed = text == null ? "" : text.strip();
        // A leading v, as in a tag, is not part of the version.
        String body = trimmed.length() > 1 && (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V')
                && Character.isDigit(trimmed.charAt(1)) ? trimmed.substring(1) : trimmed;
        // Build metadata never takes part in ordering.
        int plus = body.indexOf('+');
        if (plus >= 0) {
            body = body.substring(0, plus);
        }
        int dash = body.indexOf('-');
        String releasePart = dash < 0 ? body : body.substring(0, dash);
        String suffixPart = dash < 0 ? "" : body.substring(dash + 1);
        return new Version(trimmed, split(releasePart), split(suffixPart));
    }

    private static List<String> split(String part) {
        List<String> parts = new ArrayList<>();
        if (!part.isEmpty()) {
            for (String piece : part.split("[.-]")) {
                parts.add(piece);
            }
        }
        return List.copyOf(parts);
    }

    /** Whether this is a build between releases, such as {@code 0.2-dev.41}. */
    public boolean isPrerelease() {
        return !suffix.isEmpty();
    }

    @Override
    public int compareTo(Version other) {
        int length = Math.max(release.size(), other.release.size());
        for (int i = 0; i < length; i++) {
            // 0.2 and 0.2.0 are the same release.
            String mine = i < release.size() ? release.get(i) : "0";
            String theirs = i < other.release.size() ? other.release.get(i) : "0";
            int compared = comparePart(mine, theirs);
            if (compared != 0) {
                return compared;
            }
        }
        if (suffix.isEmpty() || other.suffix.isEmpty()) {
            return Boolean.compare(suffix.isEmpty(), other.suffix.isEmpty());
        }
        for (int i = 0; i < Math.min(suffix.size(), other.suffix.size()); i++) {
            int compared = comparePart(suffix.get(i), other.suffix.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(suffix.size(), other.suffix.size());
    }

    private static int comparePart(String a, String b) {
        boolean aNumber = isNumber(a);
        boolean bNumber = isNumber(b);
        if (aNumber && bNumber) {
            // Lengths first, so numbers of any size compare without overflow.
            String x = stripZeros(a);
            String y = stripZeros(b);
            return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
        }
        if (aNumber != bNumber) {
            // As in semver: numbers sort before words.
            return aNumber ? -1 : 1;
        }
        return a.compareTo(b);
    }

    private static boolean isNumber(String part) {
        if (part.isEmpty()) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            if (!Character.isDigit(part.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String stripZeros(String number) {
        int i = 0;
        while (i < number.length() - 1 && number.charAt(i) == '0') {
            i++;
        }
        return number.substring(i);
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        // Must agree with equals, which ignores trailing zeros and leading zeros.
        List<String> trimmed = new ArrayList<>();
        for (String part : release) {
            trimmed.add(isNumber(part) ? stripZeros(part) : part);
        }
        while (!trimmed.isEmpty() && trimmed.getLast().equals("0")) {
            trimmed.removeLast();
        }
        List<String> suffixes = new ArrayList<>();
        for (String part : suffix) {
            suffixes.add(isNumber(part) ? stripZeros(part) : part);
        }
        return 31 * trimmed.hashCode() + suffixes.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
