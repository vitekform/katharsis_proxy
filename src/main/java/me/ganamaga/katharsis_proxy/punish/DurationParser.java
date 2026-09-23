package me.ganamaga.katharsis_proxy.punish;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads punishment lengths as staff type them: {@code 30m}, {@code 7d},
 * {@code 1y2mo3w4d5h30m10s}, or {@code perm} for one that never runs out.
 *
 * <p>A month is 30 days and a year 365; nobody appeals a ban over leap days.
 * Units may come in any order and repeat; they add up.</p>
 */
public final class DurationParser {

    private static final Pattern PART = Pattern.compile("(\\d+)(mo|y|w|d|h|m|s)");
    private static final Pattern WHOLE = Pattern.compile("(?:\\d+(?:mo|y|w|d|h|m|s))+");

    private DurationParser() {
    }

    /**
     * @return the length, or empty if {@code input} is not one (including zero
     *         and lengths too long to represent)
     */
    public static Optional<PunishmentLength> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.equals("perm") || text.equals("permanent") || text.equals("forever") || text.equals("-1")) {
            return Optional.of(PunishmentLength.PERMANENT);
        }
        if (!WHOLE.matcher(text).matches()) {
            return Optional.empty();
        }
        try {
            long seconds = 0;
            Matcher matcher = PART.matcher(text);
            while (matcher.find()) {
                long amount = Long.parseLong(matcher.group(1));
                seconds = Math.addExact(seconds, Math.multiplyExact(amount, unitSeconds(matcher.group(2))));
            }
            if (seconds == 0) {
                return Optional.empty();
            }
            return Optional.of(PunishmentLength.of(Duration.ofSeconds(seconds)));
        } catch (NumberFormatException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    /**
     * Writes {@code length} back in the form {@link #parse} reads, largest
     * units first: {@code 1d12h}, {@code perm}. For logs and the Discord log,
     * where a translated phrase is not wanted.
     */
    public static String format(PunishmentLength length) {
        if (length.isPermanent()) {
            return "perm";
        }
        long seconds = length.duration().getSeconds();
        StringBuilder out = new StringBuilder();
        for (String unit : new String[]{"y", "mo", "w", "d", "h", "m", "s"}) {
            long size = unitSeconds(unit);
            if (seconds >= size) {
                out.append(seconds / size).append(unit);
                seconds %= size;
            }
        }
        return out.toString();
    }

    private static long unitSeconds(String unit) {
        return switch (unit) {
            case "y" -> 365L * 86_400;
            case "mo" -> 30L * 86_400;
            case "w" -> 7L * 86_400;
            case "d" -> 86_400;
            case "h" -> 3_600;
            case "m" -> 60;
            case "s" -> 1;
            default -> throw new IllegalArgumentException("unknown unit " + unit);
        };
    }
}
