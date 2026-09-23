package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code moderation.openai} section of {@code config.yml}: the moderation
 * model asked about messages the rules let through.
 */
@ConfigSerializable
public class OpenAiConfig {

    /** Off by default, since there is no key to call with. */
    private boolean enabled = false;

    private String apiKey = "";

    private String model = "omni-moderation-latest";

    private String url = "https://api.openai.com/v1/moderations";

    /**
     * Longer than this and the message is held back rather than waited on.
     * Generous, because the model answering slowly is not a fault: a real call
     * takes well over a second, and timing one out costs a held-back message.
     */
    private long timeoutMs = 30_000L;

    /** Quota for the key. The defaults are the free moderation tier's. */
    private int requestsPerMinute = 500;

    private int tokensPerMinute = 10_000;

    private int requestsPerDay = 10_000;

    /** Categories acted on. Empty acts on every category the model returns. */
    private List<String> categories = new ArrayList<>();

    /**
     * Flag a message when a category scores at least this, even where the model
     * did not flag it itself. 0 leaves the decision to the model.
     */
    private double scoreThreshold = 0.0d;

    public boolean enabled() {
        return enabled;
    }

    public String apiKey() {
        return apiKey == null ? "" : apiKey.strip();
    }

    public String model() {
        return model == null || model.isBlank() ? "omni-moderation-latest" : model.strip();
    }

    public String url() {
        return url == null || url.isBlank() ? "https://api.openai.com/v1/moderations" : url.strip();
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public int requestsPerMinute() {
        return requestsPerMinute;
    }

    public int tokensPerMinute() {
        return tokensPerMinute;
    }

    public int requestsPerDay() {
        return requestsPerDay;
    }

    public List<String> categories() {
        return categories == null ? List.of() : categories;
    }

    public double scoreThreshold() {
        return scoreThreshold;
    }
}
