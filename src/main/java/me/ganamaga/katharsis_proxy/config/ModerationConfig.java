package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code moderation} section of {@code config.yml}: the filters every chat
 * message passes before it is relayed and logged.
 */
@ConfigSerializable
public class ModerationConfig {

    /** While this is false chat is relayed and logged exactly as before. */
    private boolean enabled = false;

    /** Literal terms, matched however they are spelled out or stretched. */
    private List<String> words = new ArrayList<>();

    /** Regular expressions, used as written. */
    private List<String> patterns = new ArrayList<>();

    /** Innocent words a term from {@code words} is a part of. */
    private List<String> allow = new ArrayList<>();

    /** Tell the author their message was held back. */
    private boolean warnPlayer = true;

    /** Discord channel flagged messages are reported in. 0 reports none. */
    private long staffChannelId = 0L;

    private OpenAiConfig openai = new OpenAiConfig();

    public boolean enabled() {
        return enabled;
    }

    public List<String> words() {
        return words == null ? List.of() : words;
    }

    public List<String> patterns() {
        return patterns == null ? List.of() : patterns;
    }

    public List<String> allow() {
        return allow == null ? List.of() : allow;
    }

    public boolean warnPlayer() {
        return warnPlayer;
    }

    public long staffChannelId() {
        return staffChannelId;
    }

    public OpenAiConfig openai() {
        return openai == null ? new OpenAiConfig() : openai;
    }
}
