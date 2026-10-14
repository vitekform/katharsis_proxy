package me.ganamaga.katharsis_neoforge.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Encodes requests to and decodes replies from the proxy.
 *
 * <p>Deliberately free of Minecraft types so the wire format can be tested without
 * a running server.</p>
 */
public final class ProtocolCodec {

    private ProtocolCodec() {
    }

    /**
     * Builds a request.
     *
     * @param payload the optional fourth field: the language code for
     *                {@code set_language}, {@code true} or {@code false} for
     *                {@code set_discord_chat}, the plugin version for
     *                {@code announce}, ignored otherwise
     */
    public static byte[] encodeRequest(String operation, String requestId, String uuid, String payload) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(operation);
            out.writeUTF(requestId);
            out.writeUTF(uuid);
            if (carriesPayload(operation)) {
                out.writeUTF(payload);
            }
        } catch (IOException e) {
            // A ByteArrayOutputStream cannot fail.
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Builds an {@code announce} request, which carries this server's
     * configured id in a fifth field so the proxy knows which Discord chat
     * channel's messages to deliver here.
     *
     * @param serverId the configured {@code server-id}; blank means the
     *                 server's velocity.toml name
     */
    public static byte[] encodeAnnounce(String requestId, String version, String serverId) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(encodeRequest(LanguageChannel.OP_ANNOUNCE, requestId, new UUID(0L, 0L).toString(), version));
            out.writeUTF(serverId == null ? "" : serverId);
        } catch (IOException e) {
            // A ByteArrayOutputStream cannot fail.
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Operations whose request carries a fourth field. */
    private static boolean carriesPayload(String operation) {
        return LanguageChannel.OP_SET.equals(operation) || LanguageChannel.OP_ANNOUNCE.equals(operation)
                || LanguageChannel.OP_SET_DISCORD_CHAT.equals(operation);
    }

    /**
     * Decodes a reply.
     *
     * @return the response, or {@code null} if the payload was not one of ours
     */
    public static ProxyResponse decodeResponse(byte[] message) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String operation = in.readUTF();
            String requestId = in.readUTF();
            String uuid = in.readUTF();
            boolean success = in.readBoolean();
            String language = in.readUTF();
            String error = in.readUTF();

            // Three operations append extra fields after the six base ones.
            ProxyResponse.Menu menu = null;
            String serverName = "";
            ProxyResponse.Settings settings = null;
            if (success) {
                if (LanguageChannel.OP_MENU.equals(operation)) {
                    menu = readMenu(in);
                } else if (LanguageChannel.OP_ANNOUNCE.equals(operation)) {
                    serverName = in.readUTF();
                } else if (LanguageChannel.OP_SETTINGS.equals(operation)) {
                    settings = readSettings(in);
                }
            }
            return new ProxyResponse(operation, requestId, uuid, success, language, error, menu, serverName,
                    settings);
        } catch (IOException | RuntimeException e) {
            // Truncated or foreign payload; the caller drops it.
            return null;
        }
    }

    private static ProxyResponse.Menu readMenu(DataInputStream in) throws IOException {
        String title = in.readUTF();
        String selectHint = in.readUTF();
        String currentHint = in.readUTF();
        int count = in.readInt();
        if (count < 0) {
            throw new IOException("negative language count: " + count);
        }

        LinkedHashMap<String, String> languages = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            languages.put(in.readUTF(), in.readUTF());
        }
        return new ProxyResponse.Menu(title, selectHint, currentHint, languages);
    }

    private static ProxyResponse.Settings readSettings(DataInputStream in) throws IOException {
        return new ProxyResponse.Settings(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(),
                in.readBoolean(), in.readUTF(), in.readUTF(), in.readUTF(),
                in.readUTF(), in.readUTF(), in.readUTF());
    }
}
