package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.config.PunishTemplateConfig;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The templates of {@code punishments.templates}: punishments staff give by
 * name, e.g. {@code /ban Steve #hacking}, lasting longer each time the same
 * player earns the same one again.
 */
public final class TemplateRegistry {

    /**
     * A template, checked.
     *
     * @param name   as configured, without the {@code #}
     * @param type   what it gives: a ban, mute or warning
     * @param reason the reason stored
     * @param scope  where it applies, as typed after {@code -s}; {@code null} for the command's own
     * @param ladder lengths for the 1st, 2nd, ... offence; the last repeats; empty is permanent
     */
    public record Template(String name, PunishmentType type, String reason, String scope,
                           List<PunishmentLength> ladder) {

        public Template {
            ladder = List.copyOf(ladder);
        }

        /** The length for a player who already had {@code prior} of these. */
        public PunishmentLength lengthFor(int prior) {
            if (ladder.isEmpty()) {
                return PunishmentLength.PERMANENT;
            }
            return ladder.get(Math.min(Math.max(prior, 0), ladder.size() - 1));
        }
    }

    private final Map<String, Template> templates;

    private TemplateRegistry(Map<String, Template> templates) {
        this.templates = templates;
    }

    public static TemplateRegistry empty() {
        return new TemplateRegistry(Map.of());
    }

    /**
     * Reads the configured templates, skipping and reporting to {@code warn}
     * any that cannot be used.
     */
    public static TemplateRegistry load(Map<String, PunishTemplateConfig> configured, Consumer<String> warn) {
        Map<String, Template> loaded = new LinkedHashMap<>();
        configured.forEach((name, config) -> {
            String where = "punishments.templates." + name;
            if (!name.matches("[A-Za-z0-9_-]{1," + Punishment.TEMPLATE_MAX_LENGTH + "}")) {
                warn.accept(where + " has a name that is not letters, digits, _ or -; it is skipped.");
                return;
            }
            Optional<PunishmentType> type = parseType(config.type());
            if (type.isEmpty() || type.get() == PunishmentType.KICK) {
                warn.accept(where + ".type must be ban, mute or warn; the template is skipped.");
                return;
            }
            if (config.reason().isBlank()) {
                warn.accept(where + ".reason is empty; the template is skipped.");
                return;
            }
            List<PunishmentLength> ladder = new ArrayList<>();
            for (String step : config.ladder()) {
                Optional<PunishmentLength> length = DurationParser.parse(step);
                if (length.isEmpty()) {
                    warn.accept(where + ".ladder has '" + step + "', which is not a length; the template is skipped.");
                    return;
                }
                ladder.add(length.get());
            }
            loaded.put(key(name), new Template(name, type.get(), config.reason(),
                    config.scope().isBlank() ? null : config.scope(), ladder));
        });
        return new TemplateRegistry(Map.copyOf(loaded));
    }

    /** {@code name}, with or without the {@code #}, regardless of case. */
    public Optional<Template> find(String name) {
        return Optional.ofNullable(templates.get(key(name.startsWith("#") ? name.substring(1) : name)));
    }

    /** The templates' names, as configured, for tab completion. */
    public List<String> names() {
        return templates.values().stream().map(Template::name).sorted().toList();
    }

    static Optional<PunishmentType> parseType(String type) {
        try {
            return Optional.of(PunishmentType.valueOf(type.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
