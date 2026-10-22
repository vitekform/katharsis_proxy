package me.ganamaga.katharsis_proxy.messaging;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import me.ganamaga.katharsis_proxy.identity.IdentityFormat;
import me.ganamaga.katharsis_proxy.identity.NetworkIdentity;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Certifies backends' keys for the network's GNLib identity, over the
 * {@link IdentityChannel#IDENTIFIER} plugin message channel.
 *
 * <p>Only backend servers are answered. A message arriving from a player is
 * dropped: this channel, like {@code katharsis:auth}, is trusted because only
 * the proxy and its backends can speak it. A certificate goes only to a server
 * in velocity.toml, named as velocity.toml names it, for the key that server's
 * own connection carried. The companion also checks every certificate against
 * the network key it was configured with, so one forged by someone who reached
 * a backend directly is useless.</p>
 */
public final class IdentityChannelListener {

    private final ProxyServer proxy;
    private final Logger logger;
    /** The current identity, or null when there is none; changes on rotation. */
    private final Supplier<NetworkIdentity> identity;
    private final Clock clock;

    /** Servers whose first certificate has been logged, so each is logged once. */
    private final Set<String> certified = ConcurrentHashMap.newKeySet();

    public IdentityChannelListener(ProxyServer proxy, Logger logger, Supplier<NetworkIdentity> identity, Clock clock) {
        this.proxy = proxy;
        this.logger = logger;
        this.identity = identity;
        this.clock = clock;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!IdentityChannel.IDENTIFIER.equals(event.getIdentifier())) {
            return;
        }
        // Proxy-internal: never relayed to the other side.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) {
            return; // a client speaking our channel, perhaps hoping to plant a certificate
        }
        String server = source.getServerInfo().getName();
        boolean known = proxy.getServer(server).isPresent();
        List<String> problems = new ArrayList<>();
        byte[] reply = answer(event.getData(), known ? server : null, identity.get(),
                clock.millis() / 1000L, problems);
        problems.forEach(problem -> logger.warn("Skipped a network rotation statement: {}", problem));
        source.sendPluginMessage(IdentityChannel.IDENTIFIER, reply);
    }

    /**
     * The reply to one request.
     *
     * @param server   the requesting server's velocity.toml name, or null when it has none
     * @param identity the network identity, or null when there is none
     */
    byte[] answer(byte[] data, String server, NetworkIdentity identity, long nowSeconds, List<String> problems) {
        IdentityCodec.Request request = IdentityCodec.decodeRequest(data);
        if (request == null || !IdentityChannel.OP_CERT_REQUEST.equals(request.operation())) {
            logger.debug("Ignored a malformed '{}' message from '{}'.", IdentityChannel.IDENTIFIER.getId(), server);
            return IdentityCodec.encodeFailure(request == null ? "" : request.requestId(),
                    IdentityChannel.ERROR_MALFORMED_REQUEST);
        }
        if (!IdentityChannel.PROTOCOL.equals(request.protocol())) {
            logger.warn("'{}' speaks protocol '{}' on '{}', this proxy speaks '{}'. Update whichever is older.",
                    server, request.protocol(), IdentityChannel.IDENTIFIER.getId(), IdentityChannel.PROTOCOL);
        }
        if (server == null || server.getBytes(StandardCharsets.UTF_8).length > IdentityFormat.MAX_NAME_BYTES) {
            logger.warn("Refused to certify a GNLib key for a server {}.",
                    server == null ? "that is not in velocity.toml" : "whose name is over 64 bytes ('" + server + "')");
            return IdentityCodec.encodeFailure(request.requestId(), IdentityChannel.ERROR_UNKNOWN_SERVER);
        }
        if (identity == null) {
            return IdentityCodec.encodeFailure(request.requestId(), IdentityChannel.ERROR_NO_IDENTITY);
        }
        byte[] certificate = identity.certify(request.key(), server, nowSeconds);
        if (certified.add(server)) {
            logger.info("Certified the GNLib key {} of '{}' for the network identity {}.",
                    IdentityFormat.fingerprint(request.key()), server, identity.fingerprint());
        }
        return IdentityCodec.encodeCertificate(request.requestId(), certificate,
                identity.rotations(nowSeconds, problems));
    }
}
