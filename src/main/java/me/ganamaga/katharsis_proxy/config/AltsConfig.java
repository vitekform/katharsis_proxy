package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.Locale;

/**
 * The {@code punishments.alts} section: accounts sharing an address, ban
 * evasion, and what staff are told when a player joins.
 */
@ConfigSerializable
public class AltsConfig {

    /** What happens when an account joins from the address of a banned one. */
    public enum EvasionAction {
        /** Nothing. */
        IGNORE,
        /** Staff are told. */
        ALERT,
        /** The account is banned too, and staff are told. */
        BAN
    }

    /** Addresses count as shared only if both accounts used them this recently. */
    private String lookback = "30d";

    private String evasion = "alert";

    /** Tell staff when a player with warnings or banned alts joins. */
    private boolean joinAlerts = true;

    /** Warnings a player needs for their join to be announced. */
    private int joinAlertMinWarns = 1;

    public String lookback() {
        return lookback == null || lookback.isBlank() ? "30d" : lookback;
    }

    /** Anything unrecognised is taken as {@code alert}. */
    public EvasionAction evasion() {
        if (evasion == null) {
            return EvasionAction.ALERT;
        }
        return switch (evasion.strip().toLowerCase(Locale.ROOT)) {
            case "ignore", "off", "none" -> EvasionAction.IGNORE;
            case "ban" -> EvasionAction.BAN;
            default -> EvasionAction.ALERT;
        };
    }

    public boolean joinAlerts() {
        return joinAlerts;
    }

    public int joinAlertMinWarns() {
        return Math.max(1, joinAlertMinWarns);
    }
}
