package me.ganamaga.katharsis_proxy.messaging;

import org.slf4j.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Records which backend servers run the companion plugin, and the server id
 * each one reports as.
 *
 * <p>The companion owns the language selector outright — the proxy registers no
 * {@code /language} of its own — so which backends announced changes nothing.
 * It exists so the proxy log says which backends are equipped, which is the
 * only way to spot a server that is missing the companion and therefore has no
 * selector.</p>
 *
 * <p>Server ids do matter: they are the keys of {@code discord.chat-channels},
 * so they decide which backends Discord messages are delivered to. A backend
 * that has not told us its id yet is assumed to use its velocity.toml name,
 * which is also what the companion falls back to.</p>
 */
public final class CompanionRegistry {

    private final Logger logger;
    private final Set<String> servers = ConcurrentHashMap.newKeySet();

    /** Backend name to the server id it reported, for backends whose id differs from their name. */
    private final Map<String, String> serverIds = new ConcurrentHashMap<>();

    public CompanionRegistry(Logger logger) {
        this.logger = logger;
    }

    /**
     * Records that {@code serverName} runs the companion.
     *
     * @param version the companion's version, for the log line
     * @return {@code true} if this was a server we had not heard from before
     */
    public boolean announce(String serverName, String version) {
        boolean isNew = servers.add(serverName);
        if (isNew) {
            logger.info("'{}' runs the companion plugin{} and serves the language selector.",
                    serverName, version.isEmpty() ? "" : " " + version);
        }
        return isNew;
    }

    /**
     * Records the server id {@code serverName} reports as. A blank id means it
     * uses its velocity.toml name.
     */
    public void recordServerId(String serverName, String serverId) {
        String id = serverId == null || serverId.isBlank() ? serverName : serverId;
        String previous = id.equals(serverName) ? serverIds.remove(serverName) : serverIds.put(serverName, id);
        if (!id.equals(previous == null ? serverName : previous)) {
            logger.info("'{}' reports as server id '{}'.", serverName, id);
        }
    }

    /** The server id {@code serverName} reports as, which is its name until it says otherwise. */
    public String serverIdOf(String serverName) {
        return serverIds.getOrDefault(serverName, serverName);
    }

    /** Which of {@code serverNames} report as one of {@code ids}. */
    public Set<String> serversWithIds(Collection<String> serverNames, Set<String> ids) {
        return serverNames.stream()
                .filter(name -> ids.contains(serverIdOf(name)))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether any backend has announced the companion plugin. */
    public boolean hasCompanion() {
        return !servers.isEmpty();
    }

    /** Backends known to run the companion, for diagnostics. */
    public Set<String> servers() {
        return Set.copyOf(servers);
    }
}
