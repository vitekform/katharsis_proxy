package me.ganamaga.katharsis_proxy.punish;

/**
 * Permission nodes of the punishment system. Unlike the plugin's other
 * commands, nobody has any of these until they are granted.
 */
public final class PunishPermissions {

    private static final String ROOT = "katharsis.punish.";

    /** Told about every punishment issued and lifted. */
    public static final String NOTIFY = ROOT + "notify";
    /** Cannot be punished, and not caught by bans on an address they share. */
    public static final String EXEMPT = ROOT + "exempt";
    /** May punish on the whole network. */
    public static final String GLOBAL = ROOT + "global";
    /** Sees addresses in full. */
    public static final String SEE_IP = ROOT + "seeip";

    private PunishPermissions() {
    }

    /** The node that allows {@code command}, e.g. {@code katharsis.punish.ban}. */
    public static String command(String command) {
        return ROOT + command;
    }
}
