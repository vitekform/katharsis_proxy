package me.ganamaga.katharsis_neoforge.gnlib;

import com.mojang.logging.LogUtils;
import me.ganamaga.katharsis_neoforge.config.CompanionSettings;
import me.ganamaga.katharsis_neoforge.identity.BackendIdentity;
import me.ganamaga.katharsis_neoforge.protocol.IdentityCodec;
import me.ganamaga.katharsis_neoforge.protocol.ProxyPayload;
import me.ganamaga.katharsis_neoforge.proxy.ProxyLink;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Keeps this backend's certificate for the network's GNLib identity fresh, by
 * asking the proxy on {@code katharsis:identity}.
 *
 * <p>Like every plugin message the request needs a player's connection, so it
 * goes out when someone joins and, while players are online, whenever the
 * certificate is past half its life. A server that has never had one, or was
 * empty for longer than a certificate lasts, therefore offers no network
 * identity to the first player to join; that player's own join fetches it for
 * everyone after (GNLib's TODO, Q2).</p>
 */
final class CertificateRequests {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Between requests the proxy did not answer. */
    private static final long RETRY_SECONDS = 60L;

    /** After an answer that did not give a usable certificate, so the log is not flooded. */
    private static final long BACKOFF_SECONDS = 10L * 60L;

    /** How often a server with players checks whether its certificate wants renewing. */
    private static final int CHECK_INTERVAL_TICKS = 20 * 60;

    private final ProxyLink link;
    private final BackendIdentity identity;
    private final Path settingsFile;

    private String pending;
    private long nextAttempt;
    private long nextId;
    private String lastReport;
    private int ticks;

    CertificateRequests(ProxyLink link, BackendIdentity identity, Path settingsFile) {
        this.link = link;
        this.identity = identity;
        this.settingsFile = settingsFile;
        link.listen(ProxyPayload.IDENTITY, this::received);
    }

    void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            request(player);
        }
    }

    void onTick(ServerTickEvent.Post event) {
        if (++ticks % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            request(players.getFirst());
        }
    }

    private void request(ServerPlayer carrier) {
        long now = System.currentTimeMillis() / 1000L;
        if (!identity.wantsCertificate(now) || now < nextAttempt) {
            return;
        }
        nextAttempt = now + RETRY_SECONDS;
        pending = "Katharsis-identity-" + ++nextId;
        link.send(carrier, ProxyPayload.IDENTITY, IdentityCodec.encodeRequest(pending, identity.rawPublic()));
    }

    /** On the server thread. */
    private void received(ServerPlayer carrier, byte[] data) {
        IdentityCodec.Reply reply = IdentityCodec.decodeReply(data);
        if (reply == null || pending == null || !pending.equals(reply.requestId())) {
            LOGGER.debug("Ignored an unexpected message on {}.", IdentityCodec.CHANNEL);
            return;
        }
        pending = null;
        long now = System.currentTimeMillis() / 1000L;
        BackendIdentity.Result result;
        try {
            result = identity.accept(reply.success(), reply.error(), reply.certificate(), reply.rotations(), now);
        } catch (IOException e) {
            LOGGER.error("Could not store the certificate for the network's GNLib identity.", e);
            return;
        }
        switch (result.outcome()) {
            case ACCEPTED -> {
                LOGGER.info("GNLib network identity: {}.", result.message());
                lastReport = null;
            }
            case REPINNED -> {
                LOGGER.warn("GNLib network identity: {}.", result.message());
                try {
                    CompanionSettings.writeNetworkFingerprint(settingsFile, result.pin());
                } catch (IOException e) {
                    LOGGER.error("Could not write network-fingerprint={} to {}; set it by hand, or this server "
                            + "drops out of the network identity at its next restart.", result.pin(), settingsFile, e);
                }
                lastReport = null;
            }
            default -> {
                nextAttempt = now + BACKOFF_SECONDS;
                if (!result.message().equals(lastReport)) {
                    lastReport = result.message();
                    LOGGER.warn("GNLib network identity: {}.", result.message());
                }
            }
        }
    }
}
