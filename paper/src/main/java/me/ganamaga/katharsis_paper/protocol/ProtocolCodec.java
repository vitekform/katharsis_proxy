package me.ganamaga.katharsis_paper.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;

/**
 * Encodes requests to and decodes replies from the proxy.
 *
 * <p>Deliberately free of Bukkit types so the wire format can be tested without
 * a running server.</p>
 */
public final class ProtocolCodec {

    private ProtocolCodec() {
    }

    /**
     * Builds a request.
     *
     * @param payload the optional fourth field: the language code for
     *                {@code set_language}, the plugin version for
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

    /** Operations whose request carries a fourth field. */
    private static boolean carriesPayload(String operation) {
        return LanguageChannel.OP_SET.equals(operation) || LanguageChannel.OP_ANNOUNCE.equals(operation);
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

            ProxyResponse.Menu menu = null;
            if (LanguageChannel.OP_MENU.equals(operation) && success) {
                menu = readMenu(in);
            }
            return new ProxyResponse(operation, requestId, uuid, success, language, error, menu);
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
}
