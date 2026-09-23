package me.ganamaga.katharsis_proxy.punish;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The arguments of a punishment command: a target, flags, and the words
 * after them.
 *
 * <p>Flags may stand anywhere: {@code -g} (whole network), {@code -s NAME}
 * (a server or group), {@code -a} (the account only), {@code -i} (the address
 * only), {@code -q} (no staff broadcast) and {@code -f} (replace, or accept an
 * unknown name). The first other word is the target; the rest, in order, are
 * left for the command to read as a duration, a template or the reason.</p>
 *
 * @param target      what was named
 * @param words       everything else that is not a flag
 * @param global      {@code -g}
 * @param scope       the name after {@code -s}, empty if it was missing; {@code null} without {@code -s}
 * @param accountOnly {@code -a}
 * @param ipOnly      {@code -i}
 * @param silent      {@code -q}
 * @param force       {@code -f}
 */
public record PunishArgs(String target, List<String> words, boolean global, String scope, boolean accountOnly,
                         boolean ipOnly, boolean silent, boolean force) {

    /** Every flag, for tab completion. */
    public static final List<String> FLAGS = List.of("-g", "-s", "-a", "-i", "-q", "-f");

    public PunishArgs {
        words = List.copyOf(words);
    }

    /** @return the parsed arguments, or empty if no target was named */
    public static Optional<PunishArgs> parse(String[] arguments) {
        String target = null;
        List<String> words = new ArrayList<>();
        boolean global = false;
        String scope = null;
        boolean accountOnly = false;
        boolean ipOnly = false;
        boolean silent = false;
        boolean force = false;
        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i];
            switch (argument.toLowerCase(Locale.ROOT)) {
                case "-g" -> global = true;
                case "-s" -> scope = i + 1 < arguments.length ? arguments[++i] : "";
                case "-a" -> accountOnly = true;
                case "-i" -> ipOnly = true;
                case "-q" -> silent = true;
                case "-f" -> force = true;
                default -> {
                    if (argument.isEmpty()) {
                        continue;
                    }
                    if (target == null) {
                        target = argument;
                    } else {
                        words.add(argument);
                    }
                }
            }
        }
        if (target == null) {
            return Optional.empty();
        }
        return Optional.of(new PunishArgs(target, words, global, scope, accountOnly, ipOnly, silent, force));
    }

    /** The words from {@code from} on, joined back up, or {@code null} if there are none. */
    public String rest(int from) {
        return from >= words.size() ? null : String.join(" ", words.subList(from, words.size()));
    }
}
