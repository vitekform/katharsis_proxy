package me.ganamaga.katharsis_proxy.discord;

import me.ganamaga.katharsis_proxy.entity.AccountLink;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.permission.PermissionLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DiscordPunishAuthTest {

    private static final long STAFF_ROLE = 111L;
    private static final UUID PLAYER = UUID.randomUUID();
    private static final Optional<AccountLink> LINK = Optional.of(
            new AccountLink(PLAYER, "Moderator", 42L, "mod#0001", Instant.EPOCH));

    /** Grants exactly {@code nodes}; {@code null} means it cannot be told. */
    private static PermissionLookup granting(Set<String> nodes) {
        return new PermissionLookup() {
            @Override
            public Optional<Boolean> has(UUID uuid, String node) {
                return nodes == null ? Optional.empty() : Optional.of(nodes.contains(node));
            }

            @Override
            public OptionalInt meta(UUID uuid, String key) {
                return OptionalInt.empty();
            }
        };
    }

    @Test
    @DisplayName("a staff role, a linked account and every permission let a member act as that account")
    void allowed() {
        DiscordPunishAuth.Decision decision = DiscordPunishAuth.decide(Set.of(5L, STAFF_ROLE), List.of(STAFF_ROLE),
                LINK, granting(Set.of("katharsis.punish.ban", "katharsis.punish.global")),
                List.of("katharsis.punish.ban", "katharsis.punish.global"));

        assertEquals(new IssuerRef(PLAYER, "Moderator", IssuerRef.Source.DISCORD),
                assertInstanceOf(DiscordPunishAuth.Decision.Allowed.class, decision).issuer());
    }

    @Test
    @DisplayName("each requirement is checked, the role first")
    void refused() {
        assertInstanceOf(DiscordPunishAuth.Decision.NoRole.class, DiscordPunishAuth.decide(Set.of(5L),
                List.of(STAFF_ROLE), LINK, granting(Set.of("a")), List.of("a")));
        assertInstanceOf(DiscordPunishAuth.Decision.NoRole.class, DiscordPunishAuth.decide(Set.of(STAFF_ROLE),
                List.of(), LINK, granting(Set.of("a")), List.of("a")), "no roles configured means nobody");
        assertInstanceOf(DiscordPunishAuth.Decision.NotLinked.class, DiscordPunishAuth.decide(Set.of(STAFF_ROLE),
                List.of(STAFF_ROLE), Optional.empty(), granting(Set.of("a")), List.of("a")));
        assertEquals("b", assertInstanceOf(DiscordPunishAuth.Decision.NoPermission.class,
                DiscordPunishAuth.decide(Set.of(STAFF_ROLE), List.of(STAFF_ROLE), LINK, granting(Set.of("a")),
                        List.of("a", "b"))).node());
        assertInstanceOf(DiscordPunishAuth.Decision.NoPermission.class, DiscordPunishAuth.decide(Set.of(STAFF_ROLE),
                List.of(STAFF_ROLE), LINK, granting(null), List.of("a")), "unknown is no");
    }

    @Test
    @DisplayName("#ids are read, anything else is a name")
    void ids() {
        assertEquals(Optional.of(12L), DiscordPunishCommands.parseId("#12"));
        assertEquals(Optional.empty(), DiscordPunishCommands.parseId("#"));
        assertEquals(Optional.empty(), DiscordPunishCommands.parseId("#x1"));
        assertEquals(Optional.empty(), DiscordPunishCommands.parseId("Kraxie"));
    }
}
