package me.ganamaga.katharsis_proxy.auth;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Asks Mojang whether a name belongs to a paid Minecraft account.
 *
 * <p>This only says the name is taken at Mojang, not that the player joining
 * owns it. That is proven afterwards, by sending the connection through
 * Mojang's login: someone using a premium name without its account fails there
 * and never gets in.</p>
 */
public class MojangProfiles {

    /** What Mojang said about a name. */
    public enum Lookup {
        /** A Mojang account holds the name. */
        PREMIUM,
        /** No Mojang account holds the name. */
        NOT_PREMIUM,
        /** Mojang could not be asked, or gave an answer that says neither. */
        UNKNOWN
    }

    /** Every name Mojang has ever issued fits this; anything else cannot be premium. */
    private static final Pattern MOJANG_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final Logger logger;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient client;

    public MojangProfiles(Logger logger, String baseUrl, Duration timeout) {
        this.logger = logger;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Blocks for up to the timeout; call it off the event threads. */
    public Lookup lookup(String username) {
        if (!MOJANG_NAME.matcher(username).matches()) {
            return Lookup.NOT_PREMIUM;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + username))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 200 -> hasId(response.body()) ? Lookup.PREMIUM : Lookup.UNKNOWN;
                case 204, 404 -> Lookup.NOT_PREMIUM;
                default -> {
                    logger.warn("Mojang answered {} when asked about the name {}.", response.statusCode(), username);
                    yield Lookup.UNKNOWN;
                }
            };
        } catch (IOException e) {
            logger.warn("Could not ask Mojang about the name {}: {}", username, e.toString());
            return Lookup.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Lookup.UNKNOWN;
        }
    }

    private static boolean hasId(String body) {
        try {
            var profile = JsonParser.parseString(body).getAsJsonObject();
            return profile.has("id") && !profile.get("id").getAsString().isBlank();
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            return false;
        }
    }
}
