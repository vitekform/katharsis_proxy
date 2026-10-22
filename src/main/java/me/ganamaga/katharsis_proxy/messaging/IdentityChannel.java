package me.ganamaga.katharsis_proxy.messaging;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/**
 * Wire constants for the {@code katharsis:identity} channel, over which a
 * backend running GNLib asks the proxy to certify its key for the network's
 * GNLib identity. {@code docs/plugin-messaging.md} has the fields;
 * {@link IdentityCodec} reads and writes them.
 */
public final class IdentityChannel {

    public static final MinecraftChannelIdentifier IDENTIFIER =
            MinecraftChannelIdentifier.create("katharsis", "identity");

    /** Backend to proxy: certify this key. */
    public static final String OP_CERT_REQUEST = "cert_request";

    /** Proxy to backend: the certificate, and the network's rotation statements. */
    public static final String OP_CERT = "cert";

    /** Raised only when a change would be misread by the other side. */
    public static final String PROTOCOL = "1";

    public static final String ERROR_NONE = "";
    public static final String ERROR_MALFORMED_REQUEST = "malformed_request";
    /** The server the request came from is not in velocity.toml, or its name does not fit a certificate. */
    public static final String ERROR_UNKNOWN_SERVER = "unknown_server";
    /** The proxy has no usable network key; its log says why. */
    public static final String ERROR_NO_IDENTITY = "no_identity";

    private IdentityChannel() {
    }
}
