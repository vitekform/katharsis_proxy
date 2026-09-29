package me.ganamaga.katharsis_proxy.auth;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The backends named in {@code auth.login-servers}, where players log in on the
 * server itself instead of in the void, held in place by the Katharsis NeoForge
 * mod.
 *
 * <p>Whether a player skips the void is decided from the address they typed,
 * the way Velocity picks their first server. A client cannot get in unchecked
 * this way: pointing it at a login server's address only sends it to that
 * server, where the mod holds it until it logs in.</p>
 */
public final class LoginServers {

    private final Set<String> names;

    public LoginServers(Collection<String> names) {
        this.names = names.stream()
                .map(name -> name.strip().toLowerCase(Locale.ROOT))
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isEmpty() {
        return names.isEmpty();
    }

    /** Velocity's server names ignore case, and so does this. */
    public boolean contains(String server) {
        return names.contains(server.toLowerCase(Locale.ROOT));
    }

    /** Whether a player who joined through {@code virtualHost} is sent to a login server first. */
    public boolean isFirstServerFor(Optional<InetSocketAddress> virtualHost, Map<String, List<String>> forcedHosts,
                                    List<String> attemptOrder) {
        return firstServer(virtualHost, forcedHosts, attemptOrder).filter(this::contains).isPresent();
    }

    /**
     * The server Velocity tries first: the forced host for the address the
     * player typed, or else the first of the try list.
     */
    static Optional<String> firstServer(Optional<InetSocketAddress> virtualHost,
                                        Map<String, List<String>> forcedHosts, List<String> attemptOrder) {
        String host = virtualHost.map(InetSocketAddress::getHostString)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .orElse("");
        List<String> forced = forcedHosts.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(host))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of());
        List<String> order = forced.isEmpty() ? attemptOrder : forced;
        return order.stream().findFirst();
    }
}
