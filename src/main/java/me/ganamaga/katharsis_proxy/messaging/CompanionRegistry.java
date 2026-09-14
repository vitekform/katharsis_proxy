package me.ganamaga.katharsis_proxy.messaging;

import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which backend servers run the companion plugin.
 *
 * <p>The companion owns the language selector outright — the proxy registers no
 * {@code /language} of its own — so nothing here changes behaviour. It exists so
 * the proxy log says which backends are equipped, which is the only way to spot
 * a server that is missing the companion and therefore has no selector.</p>
 */
public final class CompanionRegistry {

    private final Logger logger;
    private final Set<String> servers = ConcurrentHashMap.newKeySet();

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

    /** Whether any backend has announced the companion plugin. */
    public boolean hasCompanion() {
        return !servers.isEmpty();
    }

    /** Backends known to run the companion, for diagnostics. */
    public Set<String> servers() {
        return Set.copyOf(servers);
    }
}
