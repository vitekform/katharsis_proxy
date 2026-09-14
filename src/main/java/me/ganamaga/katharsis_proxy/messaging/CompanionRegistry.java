package me.ganamaga.katharsis_proxy.messaging;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which backend servers run the companion plugin.
 *
 * <p>The companion owns the language selector, so as soon as one backend
 * announces itself the proxy drops its own {@code /language}: leaving both
 * registered would give players two different interfaces for the same setting,
 * and the proxy's would shadow the richer one.</p>
 *
 * <p>A backend can only announce once a player is connected to it, because a
 * plugin message needs a player to travel on. Until then the proxy's command
 * stays registered.</p>
 */
public final class CompanionRegistry {

    private final ProxyServer proxy;
    private final Logger logger;
    private final String commandToRelease;

    private final Set<String> servers = ConcurrentHashMap.newKeySet();
    private volatile boolean released;

    public CompanionRegistry(ProxyServer proxy, Logger logger, String commandToRelease) {
        this.proxy = proxy;
        this.logger = logger;
        this.commandToRelease = commandToRelease;
    }

    /**
     * Records that {@code serverName} runs the companion, unregistering the
     * proxy's own command the first time any backend says so.
     *
     * @param version the companion's version, for the log line
     * @return {@code true} if this was a server we had not heard from before
     */
    public synchronized boolean announce(String serverName, String version) {
        boolean isNew = servers.add(serverName);
        if (isNew) {
            logger.info("'{}' runs the companion plugin{}.", serverName,
                    version.isEmpty() ? "" : " " + version);
        }

        if (!released) {
            released = true;
            proxy.getCommandManager().unregister(commandToRelease);
            logger.info("Unregistered /{} on the proxy; the companion plugin provides the selector instead.",
                    commandToRelease);
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
