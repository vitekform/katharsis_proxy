package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.PunishmentScope;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Knows the server groups from the config, turns what staff type into a
 * {@link PunishmentScope}, and answers whether a scope covers a server.
 *
 * <p>Server and group names are compared without regard to case, as Velocity
 * does for server names. A group wins over a server of the same name; the
 * config check warns about such clashes.</p>
 */
public final class ScopeResolver {

    public static final String GLOBAL = "global";

    /** Group name, lower-cased, to its member servers, lower-cased. */
    private final Map<String, Set<String>> groups;
    /** Group name, lower-cased, to the name as configured. */
    private final Map<String, String> groupNames;

    public ScopeResolver(Map<String, ? extends Collection<String>> groups) {
        this.groups = new LinkedHashMap<>();
        this.groupNames = new LinkedHashMap<>();
        groups.forEach((name, members) -> {
            String key = key(name);
            this.groupNames.put(key, name);
            this.groups.put(key, members.stream().map(ScopeResolver::key).collect(Collectors.toUnmodifiableSet()));
        });
    }

    /**
     * Reads a scope as typed after {@code -s}: {@code global}, a group, or one
     * of {@code servers}. A server comes back spelled as {@code servers} spells
     * it.
     *
     * @return the scope, or empty if {@code input} names nothing known
     */
    public Optional<PunishmentScope> parse(String input, Collection<String> servers) {
        String key = key(input);
        if (key.equals(GLOBAL)) {
            return Optional.of(PunishmentScope.global());
        }
        String group = groupNames.get(key);
        if (group != null) {
            return Optional.of(PunishmentScope.group(group));
        }
        return servers.stream()
                .filter(server -> key(server).equals(key))
                .findFirst()
                .map(PunishmentScope::server);
    }

    /** Whether a punishment scoped to {@code scope} applies on {@code server}. */
    public boolean appliesTo(PunishmentScope scope, String server) {
        return switch (scope.kind()) {
            case GLOBAL -> true;
            case SERVER -> key(scope.name()).equals(key(server));
            case GROUP -> groups.getOrDefault(key(scope.name()), Set.of()).contains(key(server));
        };
    }

    /**
     * The scope a punishment issued from {@code server} gets when none is
     * given: the first group holding the server if {@code preferGroup}, else
     * the server itself.
     */
    public PunishmentScope defaultFor(String server, boolean preferGroup) {
        if (preferGroup) {
            String serverKey = key(server);
            for (Map.Entry<String, Set<String>> group : groups.entrySet()) {
                if (group.getValue().contains(serverKey)) {
                    return PunishmentScope.group(groupNames.get(group.getKey()));
                }
            }
        }
        return PunishmentScope.server(server);
    }

    /** The configured group names, as configured. */
    public List<String> groupNames() {
        return List.copyOf(groupNames.values());
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
