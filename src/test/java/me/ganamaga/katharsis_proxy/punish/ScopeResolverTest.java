package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeResolverTest {

    private static final List<String> SERVERS = List.of("lobby", "Lifesteal", "lifesteal2", "creative");

    private final ScopeResolver resolver;

    ScopeResolverTest() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("Survival", List.of("lifesteal", "LIFESTEAL2"));
        groups.put("everything", List.of("lobby", "lifesteal", "lifesteal2", "creative"));
        resolver = new ScopeResolver(groups);
    }

    @Test
    @DisplayName("parse reads global, groups and servers regardless of case")
    void parse() {
        assertEquals(PunishmentScope.global(), resolver.parse("GLOBAL", SERVERS).orElseThrow());
        assertEquals(PunishmentScope.group("Survival"), resolver.parse("survival", SERVERS).orElseThrow());
        assertEquals(PunishmentScope.server("Lifesteal"), resolver.parse("lifesteal", SERVERS).orElseThrow(),
                "spelled as Velocity spells it");
        assertTrue(resolver.parse("nether", SERVERS).isEmpty());
    }

    @Test
    @DisplayName("appliesTo matches global, the one server, or the group's members")
    void appliesTo() {
        assertTrue(resolver.appliesTo(PunishmentScope.global(), "anything"));
        assertTrue(resolver.appliesTo(PunishmentScope.server("Lifesteal"), "lifesteal"));
        assertFalse(resolver.appliesTo(PunishmentScope.server("lifesteal"), "lifesteal2"));
        assertTrue(resolver.appliesTo(PunishmentScope.group("survival"), "LifeSteal2"));
        assertFalse(resolver.appliesTo(PunishmentScope.group("Survival"), "lobby"));
        assertFalse(resolver.appliesTo(PunishmentScope.group("removed"), "lobby"),
                "a group no longer configured covers nothing");
    }

    @Test
    @DisplayName("the default scope is the first group holding the server, or the server")
    void defaultFor() {
        assertEquals(PunishmentScope.group("Survival"), resolver.defaultFor("lifesteal", true));
        assertEquals(PunishmentScope.server("lifesteal"), resolver.defaultFor("lifesteal", false));
        assertEquals(PunishmentScope.group("everything"), resolver.defaultFor("lobby", true));
        assertEquals(PunishmentScope.server("hub"), resolver.defaultFor("hub", true));
    }
}
