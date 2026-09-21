package me.ganamaga.katharsis_proxy.moderation;

/** Which layer of the filter stopped a message. */
public enum ModerationSource {

    /** A rule from {@code moderation.words} or {@code moderation.patterns}. */
    RULES("rules"),

    /** OpenAI's moderation model. */
    OPENAI("openai"),

    /**
     * Nothing decided: the moderation call could not be made or its answer
     * could not be read, and the message was held back rather than let through
     * unchecked. Not a finding against the player.
     */
    UNAVAILABLE("unavailable");

    private final String id;

    ModerationSource(String id) {
        this.id = id;
    }

    /** Short name stored in the database and shown in the log. */
    public String id() {
        return id;
    }

    /** Whether this source means the player actually wrote something. */
    public boolean isViolation() {
        return this != UNAVAILABLE;
    }
}
