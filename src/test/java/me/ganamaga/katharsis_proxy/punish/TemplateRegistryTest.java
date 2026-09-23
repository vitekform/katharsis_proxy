package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRegistryTest {

    /** Loads {@code yaml} as the {@code punishments} section of a config. */
    static ConfigManager config(Path directory, String yaml) throws Exception {
        Files.writeString(directory.resolve("config.yml"), "punishments:\n" + yaml.indent(2));
        ConfigManager manager = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        manager.load();
        return manager;
    }

    @Test
    @DisplayName("templates load with their ladder; broken ones are reported and skipped")
    void load(@TempDir Path directory) throws Exception {
        ConfigManager manager = config(directory, """
                templates:
                  Hacking:
                    type: ban
                    reason: Cheating
                    scope: global
                    ladder: [7d, 30d, perm]
                  toxicity:
                    type: WARN
                    reason: Toxic
                  badtype:
                    type: kick
                    reason: x
                  badladder:
                    type: mute
                    reason: x
                    ladder: [1h, soon]
                  noreason:
                    type: ban
                  "bad name":
                    type: ban
                    reason: x
                """);
        List<String> warnings = new ArrayList<>();

        TemplateRegistry registry = TemplateRegistry.load(manager.config().punishments().templates(), warnings::add);

        assertEquals(List.of("Hacking", "toxicity"), registry.names());
        assertEquals(4, warnings.size(), String.join("\n", warnings));
        TemplateRegistry.Template hacking = registry.find("#hacking").orElseThrow();
        assertEquals(PunishmentType.BAN, hacking.type());
        assertEquals("global", hacking.scope());
        TemplateRegistry.Template toxicity = registry.find("TOXICITY").orElseThrow();
        assertEquals(PunishmentType.WARN, toxicity.type());
        assertNull(toxicity.scope());
        assertTrue(registry.find("spam").isEmpty());
    }

    @Test
    @DisplayName("each offence takes the next step of the ladder, the last repeating")
    void ladder() {
        TemplateRegistry.Template template = new TemplateRegistry.Template("hacking", PunishmentType.BAN, "Cheating",
                null, List.of(PunishmentLength.of(Duration.ofDays(7)), PunishmentLength.of(Duration.ofDays(30)),
                PunishmentLength.PERMANENT));
        assertEquals(Duration.ofDays(7), template.lengthFor(0).duration());
        assertEquals(Duration.ofDays(30), template.lengthFor(1).duration());
        assertTrue(template.lengthFor(2).isPermanent());
        assertTrue(template.lengthFor(9).isPermanent());
        assertTrue(new TemplateRegistry.Template("x", PunishmentType.BAN, "x", null, List.of())
                .lengthFor(0).isPermanent(), "no ladder is permanent");
    }
}
