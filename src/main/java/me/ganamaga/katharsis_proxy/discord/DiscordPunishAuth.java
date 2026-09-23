package me.ganamaga.katharsis_proxy.discord;

import me.ganamaga.katharsis_proxy.entity.AccountLink;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.permission.PermissionLookup;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a Discord member may use a {@code /punish} subcommand. All
 * of these are required:
 *
 * <ol>
 *   <li>one of the configured staff roles, so the command can be handed out
 *       and taken away on Discord;</li>
 *   <li>a Minecraft account linked with {@code /link};</li>
 *   <li>that account holding every permission the same command needs in game,
 *       so Discord never lets anyone do more than they could in game.</li>
 * </ol>
 *
 * <p>What they issue is recorded as issued by the linked account.</p>
 */
public final class DiscordPunishAuth {

    /** The outcome. */
    public sealed interface Decision {

        /** May go ahead, as {@code issuer}. */
        record Allowed(IssuerRef issuer) implements Decision {
        }

        /** Holds none of the staff roles. */
        record NoRole() implements Decision {
        }

        /** Has no linked Minecraft account. */
        record NotLinked() implements Decision {
        }

        /** The linked account lacks {@code node}, or it cannot be told. */
        record NoPermission(String node) implements Decision {
        }
    }

    private DiscordPunishAuth() {
    }

    /**
     * @param memberRoles the member's role ids
     * @param staffRoles  the configured staff role ids; none means nobody is staff
     * @param link        the member's linked Minecraft account, if any
     * @param nodes       the permissions the command needs
     */
    public static Decision decide(Collection<Long> memberRoles, List<Long> staffRoles, Optional<AccountLink> link,
                                  PermissionLookup permissions, List<String> nodes) {
        if (staffRoles.stream().noneMatch(memberRoles::contains)) {
            return new Decision.NoRole();
        }
        if (link.isEmpty()) {
            return new Decision.NotLinked();
        }
        for (String node : nodes) {
            if (!permissions.has(link.get().playerUuid(), node).orElse(false)) {
                return new Decision.NoPermission(node);
            }
        }
        return new Decision.Allowed(new IssuerRef(link.get().playerUuid(), link.get().playerName(),
                IssuerRef.Source.DISCORD));
    }
}
