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

    private static DefaultScope parseScope(String value, DefaultScope fallback) {
        if (value == null) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT).equals("global") ? DefaultScope.GLOBAL : fallback;
    }
}
