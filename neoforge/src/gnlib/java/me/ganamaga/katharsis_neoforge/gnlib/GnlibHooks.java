package me.ganamaga.katharsis_neoforge.gnlib;

import me.ganamaga.gnlib.api.remote.RemoteAuthGate;
import me.ganamaga.gnlib.api.remote.RemoteAuthGates;
import me.ganamaga.gnlib.api.remote.RemoteContent;
import me.ganamaga.katharsis_neoforge.identity.BackendIdentity;
import me.ganamaga.katharsis_neoforge.lock.Lock;
import me.ganamaga.katharsis_neoforge.lock.Locks;
import me.ganamaga.katharsis_neoforge.proxy.ProxyLink;

/**
 * The only class that names GNLib's types. Loaded only from
 * {@link GnlibSupport#install}, once GNLib is known to be installed.
 */
final class GnlibHooks {

    private GnlibHooks() {
    }

    /** @param identity null when this server has no usable key; GNLib then closes handshakes with NO_IDENTITY */
    static void install(Locks locks, BackendIdentity identity) {
        // Held unless the connection has a lock that is already open, which in
        // configuration it never is: the lock opens only on the proxy's unlock,
        // and only once the player exists. "No lock yet" counts as held too,
        // since GNLib may ask before this mod's configuration listener has run,
        // and a connection that ends up without a lock is refused anyway.
        RemoteAuthGates.register((profile, connection) -> {
            Lock lock = Locks.of(connection);
            return lock == null || lock.isLocked() ? RemoteAuthGate.State.HELD : RemoteAuthGate.State.OPEN;
        });
        // GNLib's release sends the content keys, and the client's answers must
        // pass the lock, so it runs after the lock is open (Lock#receive), on
        // the server thread as GNLib requires.
        locks.onUnlock((player, connection) -> ProxyLink.server(player).execute(() -> RemoteAuthGates.release(connection)));
        RemoteContent.setCertificateProvider(() -> {
            BackendIdentity.Presented presented = identity == null
                    ? null : identity.presented(System.currentTimeMillis() / 1000L);
            return presented == null ? null : new RemoteContent.Chain(presented.networkKey(),
                    presented.backendKey(), presented.certificate(), presented.rotations());
        });
    }
}
