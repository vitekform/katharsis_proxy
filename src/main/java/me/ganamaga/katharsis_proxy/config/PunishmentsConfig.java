package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code punishments} section of {@code config.yml}: bans, mutes, kicks
 * and warnings, and where they apply.
 */
@ConfigSerializable
public class PunishmentsConfig {

    /** Where a punishment applies when the command names no scope. */
    public enum DefaultScope {
        /** The server the staff member is on; global from the console. */
        CURRENT_SERVER,
        /** The whole network. */
        GLOBAL
    }

    private boolean enabled = true;

    /** Group name to the {@code velocity.toml} servers in it. */
    private Map<String, List<String>> serverGroups = new LinkedHashMap<>();

    private String defaultScope = "current-server";

    /** From a server that is in a group, punish on the whole group. */
    private boolean defaultScopeUsesGroup = true;

    /** Scope of punishments from the console and Discord when none is named. */
    private String consoleDefaultScope = "global";

    /** {@code /ban} also bans the target's last address unless {@code -a} is given. */
    private boolean banIpByDefault = true;

    /** Stored when a staff member gives no reason. */
    private String defaultReason = "No reason given";

    /** Shown on the ban screen; empty shows nothing. */
    private String appealUrl = "";

    /** Refuse logins while punishments cannot be checked, rather than let everyone in. */
    private boolean failClosed = false;

    /**
     * Addresses shared by many players through no fault of theirs, e.g. a
     * reverse proxy's own: never banned, and never taken as linking accounts.
     */
    private List<String> trustedProxyIps = new ArrayList<>();

    /** Commands a muted player cannot use, as they would talk through them. */
    private List<String> mutedCommands = new ArrayList<>(List.of(
            "msg", "tell", "w", "whisper", "m", "t", "pm", "dm", "r", "reply", "me", "say", "mail"));

    private WarnsConfig warns = new WarnsConfig();

    /** Template name, without the {@code #}, to what it gives. */
    private Map<String, PunishTemplateConfig> templates = new LinkedHashMap<>();

    private AutoModConfig automod = new AutoModConfig();

    public boolean enabled() {
        return enabled;
    }

    public Map<String, List<String>> serverGroups() {
        return serverGroups == null ? Map.of() : serverGroups;
    }

    /** Anything but {@code global} is taken as {@code current-server}. */
    public DefaultScope defaultScope() {
        return parseScope(defaultScope, DefaultScope.CURRENT_SERVER);
    }

    public boolean defaultScopeUsesGroup() {
        return defaultScopeUsesGroup;
    }

    /** Without a server to go by, anything but {@code global} names a server or group to use. */
    public String consoleDefaultScope() {
        return consoleDefaultScope == null || consoleDefaultScope.isBlank() ? "global" : consoleDefaultScope;
    }

    public boolean banIpByDefault() {
        return banIpByDefault;
    }

    public String defaultReason() {
        return defaultReason == null || defaultReason.isBlank() ? "No reason given" : defaultReason;
    }

    public String appealUrl() {
        return appealUrl == null ? "" : appealUrl;
    }

    public boolean failClosed() {
        return failClosed;
    }

    public List<String> trustedProxyIps() {
        return trustedProxyIps == null ? List.of() : trustedProxyIps;
    }

    /** Lower-cased, without a leading slash; the namespace of {@code ns:cmd} is matched either way. */
    public List<String> mutedCommands() {
        if (mutedCommands == null) {
            return List.of();
        }
        return mutedCommands.stream()
                .map(command -> command.strip().toLowerCase(Locale.ROOT))
                .map(command -> command.startsWith("/") ? command.substring(1) : command)
                .filter(command -> !command.isEmpty())
                .distinct()
                .toList();
    }

    public WarnsConfig warns() {
        return warns == null ? new WarnsConfig() : warns;
    }

    public Map<String, PunishTemplateConfig> templates() {
        return templates == null ? Map.of() : templates;
    }

    public AutoModConfig automod() {
        return automod == null ? new AutoModConfig() : automod;
    }

    private static DefaultScope parseScope(String value, DefaultScope fallback) {
        if (value == null) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT).equals("global") ? DefaultScope.GLOBAL : fallback;
    }
}
