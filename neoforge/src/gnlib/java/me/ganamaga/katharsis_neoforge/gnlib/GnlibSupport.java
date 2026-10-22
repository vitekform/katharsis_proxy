package me.ganamaga.katharsis_neoforge.gnlib;

import com.mojang.logging.LogUtils;
import me.ganamaga.katharsis_neoforge.config.CompanionSettings;
import me.ganamaga.katharsis_neoforge.identity.BackendIdentity;
import me.ganamaga.katharsis_neoforge.lock.Locks;
import me.ganamaga.katharsis_neoforge.proxy.ProxyLink;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * GNLib remote content behind the proxy, when GNLib is installed: the login
 * lock holds GNLib's auth gate, and this server presents the network's identity
 * with a certificate from the proxy.
 *
 * <p>Nothing here names a GNLib type, so this class loads without GNLib; only
 * {@link GnlibHooks} does, and it is reached only once GNLib is known to be
 * there. Without GNLib the mod works exactly as before.</p>
 *
 * <p>This is the 1.21.1 line's copy, the only line GNLib serves remote content
 * on; the other lines build a version that does nothing.</p>
 */
public final class GnlibSupport {

    public static final String GNLIB_ID = "gnlib";

    private static final Logger LOGGER = LogUtils.getLogger();

    private GnlibSupport() {
    }

    /** From the mod's constructor. */
    public static void install(Locks locks, ProxyLink link, CompanionSettings settings, Path settingsFile) {
        if (!ModList.get().isLoaded(GNLIB_ID)) {
            return;
        }
        Path directory = FMLPaths.GAMEDIR.get().resolve("gnlib-remote").resolve("identity");
        BackendIdentity identity;
        try {
            identity = BackendIdentity.load(directory, settings.networkFingerprint(), System.currentTimeMillis() / 1000L);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not set up this server's key for the network's GNLib identity in {}; GNLib players "
                    + "get no remote content here.", directory, e);
            identity = null;
        }
        if (identity != null && identity.problem() != null) {
            LOGGER.error("No key for the network's GNLib identity, so GNLib players get no remote content here: {}",
                    identity.problem());
        } else if (identity != null && identity.pin() == null) {
            // Crypto review F8: no trust-the-first-one. The fingerprint the proxy presents is logged when it answers.
            LOGGER.warn("network-fingerprint is not set in {}, so this server presents no GNLib network identity and "
                    + "GNLib players get no remote content here. Set it to the fingerprint /kidentity prints on "
                    + "the proxy.", settingsFile);
        }
        if (identity != null && identity.problem() == null) {
            CertificateRequests requests = new CertificateRequests(link, identity, settingsFile);
            NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, requests::onLoggedIn);
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, requests::onTick);
        }
        GnlibHooks.install(locks, identity);
        LOGGER.info("GNLib is installed: its remote content waits for each player's login on the proxy, and this "
                + "server presents the network's identity ({}).",
                identity == null || identity.pin() == null ? "none configured" : identity.pin());
    }
}
