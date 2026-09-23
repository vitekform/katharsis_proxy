package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.permission.PermissionLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionHierarchyTest {

    private final Set<String> granted = new HashSet<>();
    private final Map<UUID, Integer> weights = new HashMap<>();
    private final Set<UUID> unknown = new HashSet<>();

    /** Answers from the sets above; players in {@code unknown} cannot be asked about. */
    private final PermissionLookup lookup = new PermissionLookup() {
        @Override
        public Optional<Boolean> has(UUID uuid, String node) {
            return unknown.contains(uuid) ? Optional.empty() : Optional.of(granted.contains(uuid + node));
        }

        @Override
        public OptionalInt meta(UUID uuid, String key) {
            Integer weight = weights.get(uuid);
            return weight == null || unknown.contains(uuid) ? OptionalInt.empty() : OptionalInt.of(weight);
        }
    };

    private final PermissionHierarchy hierarchy = new PermissionHierarchy(lookup);
    private final UUID mod = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();

    private IssuerRef staff(UUID uuid) {
        return new IssuerRef(uuid, "Staff", IssuerRef.Source.GAME);
    }

    @Test
    @DisplayName("without weights, anyone may punish anyone not exempt")
    void noWeights() {
        assertEquals(Hierarchy.Verdict.ALLOWED, hierarchy.check(staff(mod), player));
        assertEquals(Hierarchy.Verdict.ALLOWED, hierarchy.check(staff(mod), admin));
    }

    @Test
    @DisplayName("nobody may punish the exempt, not even the console")
    void exempt() {
        granted.add(admin + PunishPermissions.EXEMPT);
        assertEquals(Hierarchy.Verdict.EXEMPT, hierarchy.check(staff(mod), admin));
        assertEquals(Hierarchy.Verdict.EXEMPT, hierarchy.check(IssuerRef.console(), admin));
    }

    @Test
    @DisplayName("staff cannot punish anyone at or above their weight, unless they may override")
    void weights() {
        weights.put(mod, 10);
        weights.put(admin, 50);
        UUID otherMod = UUID.randomUUID();
        weights.put(otherMod, 10);

        assertEquals(Hierarchy.Verdict.OUTRANKED, hierarchy.check(staff(mod), admin));
        assertEquals(Hierarchy.Verdict.OUTRANKED, hierarchy.check(staff(mod), otherMod), "equal weight");
        assertEquals(Hierarchy.Verdict.ALLOWED, hierarchy.check(staff(admin), mod));
        assertEquals(Hierarchy.Verdict.ALLOWED, hierarchy.check(staff(mod), player), "a player weighs nothing");
        assertEquals(Hierarchy.Verdict.OUTRANKED, hierarchy.check(staff(player), mod), "nor does unranked staff");
        assertEquals(Hierarchy.Verdict.ALLOWED, hierarchy.check(IssuerRef.console(), admin));

        granted.add(mod + PunishPermissions.OVERRIDE);
        assertEquals(Hierarchy.Verdict.ALLOWED, hierarchy.check(staff(mod), admin));
    }

    @Test
    @DisplayName("a player whose permissions cannot be told is neither exempt nor ranked")
    void unknownPlayer() {
        granted.add(admin + PunishPermissions.EXEMPT);
        weights.put(admin, 50);
        unknown.add(admin);
        assertEquals(Hierarchy.Verdict.ALLOWED, hierarchy.check(staff(mod), admin));
    }
}
