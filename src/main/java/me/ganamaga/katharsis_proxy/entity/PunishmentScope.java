package me.ganamaga.katharsis_proxy.entity;

import java.util.Locale;
import java.util.Objects;

/**
 * Where a {@link Punishment} applies: everywhere, one backend server, or a
 * group of servers named in the config.
 *
 * <p>Server names are the ones in {@code velocity.toml}. A group is kept by
 * name and resolved against the config whenever it is checked, so editing a
 * group moves the punishments already issued to it along with it.</p>
 *
 * @param kind which of the three this is
 * @param name the server or group name; {@code null} for {@link Kind#GLOBAL}
 */
public record PunishmentScope(Kind kind, String name) {

    /** Width of the {@code scope_name} column. */
    public static final int NAME_MAX_LENGTH = 64;

    private static final PunishmentScope GLOBAL = new PunishmentScope(Kind.GLOBAL, null);

    public enum Kind {
        GLOBAL, SERVER, GROUP;

        /** The form stored in the database. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromKey(String key) {
            return valueOf(key.toUpperCase(Locale.ROOT));
        }
    }

    public PunishmentScope {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.GLOBAL) {
            if (name != null) {
                throw new IllegalArgumentException("a global scope has no name");
            }
        } else {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty() || name.length() > NAME_MAX_LENGTH) {
                throw new IllegalArgumentException("scope name must be 1-" + NAME_MAX_LENGTH + " characters");
            }
        }
    }

    public static PunishmentScope global() {
        return GLOBAL;
    }

    public static PunishmentScope server(String name) {
        return new PunishmentScope(Kind.SERVER, name);
    }

    public static PunishmentScope group(String name) {
        return new PunishmentScope(Kind.GROUP, name);
    }

    public boolean isGlobal() {
        return kind == Kind.GLOBAL;
    }

    /** {@code global}, or the server or group name; for logs and plain-text output. */
    public String describe() {
        return isGlobal() ? "global" : name;
    }
}
