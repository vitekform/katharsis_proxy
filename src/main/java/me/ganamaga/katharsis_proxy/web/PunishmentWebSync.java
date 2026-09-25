package me.ganamaga.katharsis_proxy.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.ganamaga.katharsis_proxy.config.PunishWebConfig;
import me.ganamaga.katharsis_proxy.database.PunishmentRepository;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.punish.PunishmentObserver;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Keeps the website's public punishment list in step with this proxy.
 *
 * <p>Every punishment issued or lifted is pushed as it happens, and the whole
 * table is sent again at startup and every {@code resync-minutes}. The website
 * matches rows by id, so sending one twice is harmless, and a push lost while
 * the website was down is made good by the next full send.</p>
 *
 * <p>Addresses never leave the proxy. Punishments on an address alone, and
 * quiet ones unless {@code include-silent} is on, are not published; their ids
 * are sent as removed instead, so turning {@code include-silent} off takes
 * them back down.</p>
 *
 * <p>All sending happens on one background thread, in order, so a revocation
 * cannot overtake the punishment it lifts.</p>
 */
public final class PunishmentWebSync implements PunishmentObserver {

    /** Where on the website the punishments go. */
    public static final String PATH = "/api/kathars/integration/punishments";

    static final String TOKEN_HEADER = "X-Kathars-Token";

    /** Punishments per request during a full send. */
    static final int BATCH = 200;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Logger logger;
    private final HttpClient http;
    private final PunishmentRepository punishments;
    private final Supplier<PunishWebConfig> config;
    private final ScheduledExecutorService executor;

    /** Set while the website cannot be reached, so an outage is logged once rather than per punishment. */
    private final AtomicBoolean failing = new AtomicBoolean();

    /**
     * @param executor runs every request; should have a single thread, so they
     *                 go out in order. Shut down by {@link #stop()}.
     */
    public PunishmentWebSync(Logger logger, HttpClient http, PunishmentRepository punishments,
                             Supplier<PunishWebConfig> config, ScheduledExecutorService executor) {
        this.logger = logger;
        this.http = http;
        this.punishments = punishments;
        this.config = config;
        this.executor = executor;
    }

    /** Sends the whole table now, and again every {@code resync-minutes} unless that is 0. */
    public void start() {
        int minutes = config.get().resyncMinutes();
        if (minutes > 0) {
            executor.scheduleWithFixedDelay(this::resync, 0L, minutes, TimeUnit.MINUTES);
        } else {
            executor.execute(this::resync);
        }
    }

    /** Stops sending; whatever is still queued is dropped and caught up by the next start. */
    public void stop() {
        executor.shutdownNow();
    }

    @Override
    public void issued(Punishment punishment) {
        submit(punishment);
    }

    @Override
    public void revoked(Punishment punishment) {
        submit(punishment);
    }

    private void submit(Punishment punishment) {
        try {
            executor.execute(() -> send(List.of(punishment)));
        } catch (RejectedExecutionException e) {
            // Shutting down; the next full send covers it.
            logger.debug("Did not send punishment #{} to the website: {}", punishment.id(), e.getMessage());
        }
    }

    /**
     * Sends every punishment, a batch at a time, blocking until done. Stops at
     * the first batch that fails; the next full send starts over.
     *
     * @return whether everything was sent
     */
    public boolean resync() {
        long afterId = 0L;
        int sent = 0;
        try {
            while (true) {
                List<Punishment> page = punishments.findPage(afterId, BATCH);
                if (page.isEmpty()) {
                    break;
                }
                if (!send(page)) {
                    return false;
                }
                sent += page.size();
                afterId = page.getLast().id();
                if (page.size() < BATCH) {
                    break;
                }
            }
        } catch (SQLException e) {
            logger.warn("Could not read punishments to send to the website.", e);
            return false;
        } catch (RuntimeException e) {
            // Thrown out of a scheduled task it would cancel every later run.
            logger.warn("Sending punishments to the website failed.", e);
            return false;
        }
        logger.debug("Sent {} punishment(s) to the website.", sent);
        return true;
    }

    /** Sends {@code batch} in one request. Never throws; returns whether the website took it. */
    boolean send(List<Punishment> batch) {
        PunishWebConfig web = config.get();
        URI endpoint = endpoint(web.url());
        if (!web.enabled() || endpoint == null || web.token().isEmpty()) {
            return false;
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, web.token())
                .timeout(Duration.ofSeconds(web.timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload(batch, web.includeSilent())),
                        StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return failed("answered " + response.statusCode() + ": " + abbreviate(response.body()));
            }
        } catch (IOException e) {
            return failed(e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (failing.compareAndSet(true, false)) {
            logger.info("The website takes punishments again.");
        }
        return true;
    }

    private boolean failed(String why) {
        if (failing.compareAndSet(false, true)) {
            logger.warn("Could not send punishments to the website ({}); retrying with the next full send.", why);
        } else {
            logger.debug("Could not send punishments to the website: {}", why);
        }
        return false;
    }

    /** The punishments endpoint under {@code url}, or {@code null} if {@code url} is not an http(s) address. */
    public static URI endpoint(String url) {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            return null;
        }
        try {
            return URI.create(base + PATH);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether {@code punishment} belongs on the public list. */
    static boolean publishes(Punishment punishment, boolean includeSilent) {
        return punishment.targetUuid() != null && (includeSilent || !punishment.silent());
    }

    /** The request body: what to publish, and the ids to take down. */
    static JsonObject payload(List<Punishment> batch, boolean includeSilent) {
        JsonArray published = new JsonArray();
        JsonArray removed = new JsonArray();
        for (Punishment punishment : batch) {
            if (publishes(punishment, includeSilent)) {
                published.add(toJson(punishment));
            } else {
                removed.add(punishment.id());
            }
        }
        JsonObject body = new JsonObject();
        body.add("punishments", published);
        body.add("removed", removed);
        return body;
    }

    /** Everything the website shows, and nothing it should not: no address, no uuids of staff. */
    static JsonObject toJson(Punishment punishment) {
        JsonObject json = new JsonObject();
        json.addProperty("id", punishment.id());
        json.addProperty("type", punishment.type().name());
        json.addProperty("playerUuid", punishment.targetUuid().toString());
        json.addProperty("playerName", punishment.targetName());
        json.addProperty("reason", punishment.reason());
        json.addProperty("server", punishment.scope().isGlobal() ? null : punishment.scope().name());
        json.addProperty("staffName", punishment.issuer().name());
        json.addProperty("staffSource", punishment.issuer().source().key());
        json.addProperty("createdAt", punishment.createdAt().toString());
        json.addProperty("expiresAt", punishment.expiresAt() == null ? null : punishment.expiresAt().toString());
        Punishment.Revocation revocation = punishment.revocation();
        json.addProperty("revokedAt", revocation == null ? null : revocation.at().toString());
        json.addProperty("revokedByName", revocation == null ? null : revocation.by().name());
        return json;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
