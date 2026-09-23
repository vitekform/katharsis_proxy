package me.ganamaga.katharsis_proxy.punish;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Picks where a player banned from one server goes instead.
 */
public final class ServerRouter {

    private ServerRouter() {
    }

    /**
     * The first server in {@code order}, Velocity's {@code try} list, that is
     * neither in {@code exclude} nor {@code banned} for the player.
     */
    public static Optional<String> firstAllowed(List<String> order, Collection<String> exclude,
                                                Predicate<String> banned) {
        Set<String> excluded = exclude.stream().map(ServerRouter::key).collect(Collectors.toSet());
        return order.stream()
                .filter(server -> !excluded.contains(key(server)))
                .filter(server -> !banned.test(server))
                .findFirst();
    }

    private static String key(String server) {
        return server.toLowerCase(Locale.ROOT);
    }
}
