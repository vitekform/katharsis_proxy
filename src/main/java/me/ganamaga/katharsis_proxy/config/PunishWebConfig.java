package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * The {@code punishments.web} section: the public punishment list on the
 * website, which this proxy keeps filled.
 */
@ConfigSerializable
public class PunishWebConfig {

    private boolean enabled = false;

    /** The website's address, e.g. {@code https://kathars.eu}; the API path is added to it. */
    private String url = "";

    /** Shared secret, the website's {@code KATHARS_INTEGRATION_TOKEN}. */
    private String token = "";

    /** Also publish punishments given quietly, with {@code -s}. */
    private boolean includeSilent = false;

    /** Minutes between sending the whole list again; 0 sends it only at startup. */
    private int resyncMinutes = 30;

    /** How long to wait for the website to answer one request. */
    private int timeoutSeconds = 10;

    public boolean enabled() {
        return enabled;
    }

    public String url() {
        return url == null ? "" : url.strip();
    }

    public String token() {
        return token == null ? "" : token.strip();
    }

    public boolean includeSilent() {
        return includeSilent;
    }

    public int resyncMinutes() {
        return Math.max(0, resyncMinutes);
    }

    public int timeoutSeconds() {
        return timeoutSeconds <= 0 ? 10 : timeoutSeconds;
    }
}
