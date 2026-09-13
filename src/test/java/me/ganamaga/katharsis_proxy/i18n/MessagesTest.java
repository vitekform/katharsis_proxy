package me.ganamaga.katharsis_proxy.i18n;

import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurateException;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Messages messages;

    @BeforeEach
    void setUp(@TempDir Path directory) throws ConfigurateException {
        LanguageRegistry registry = new LanguageRegistry(LoggerFactory.getLogger(MessagesTest.class), directory);
        registry.load("en_US");
        messages = new Messages(registry, new PlayerLanguageCache());
    }

    private String plain(Component component) {
        return PLAIN.serialize(component);
    }

    @Test
    @DisplayName("the same key renders differently per language")
    void rendersPerLanguage() {
        var server = Placeholder.unparsed("server", "lifesteal");

        assertEquals("Sending you to lifesteal...",
                plain(messages.render("en_US", MessageKey.CONNECTING, server)));
        assertEquals("Přesouvám tě na lifesteal...",
                plain(messages.render("cs_CZ", MessageKey.CONNECTING, server)));
    }

    @Test
    @DisplayName("the prefix is prepended and translated")
    void prefixIsApplied() {
        assertEquals("[Katharsis] Only players can use this command.",
                plain(messages.prefixed("en_US", MessageKey.PLAYERS_ONLY)));
        assertEquals("[Katharsis] Tento příkaz mohou použít pouze hráči.",
                plain(messages.prefixed("cs_CZ", MessageKey.PLAYERS_ONLY)));
    }

    @Test
    @DisplayName("MiniMessage formatting in the template is applied")
    void formattingIsApplied() {
        Component rendered = messages.render("en_US", MessageKey.CONNECTING,
                Placeholder.unparsed("server", "lobby"));

        assertEquals(NamedTextColor.GRAY, rendered.color(), "the template opens with <gray>");
        assertEquals(NamedTextColor.GOLD, rendered.children().get(0).color(), "<server> is wrapped in <gold>");
    }

    @Test
    @DisplayName("a substituted value cannot inject MiniMessage tags")
    void placeholdersCannotInjectTags() {
        var hostile = Placeholder.unparsed("server", "<red>hacked</red><bold>");

        assertEquals("Sending you to <red>hacked</red><bold>...",
                plain(messages.render("en_US", MessageKey.CONNECTING, hostile)));
    }

    @ParameterizedTest
    @DisplayName("durations round up and use the language's own unit layout")
    @CsvSource({
            "en_US,65000,1m 5s",
            "en_US,60000,1m 0s",
            "en_US,45000,45s",
            "en_US,999,1s",
            "en_US,0,1s",
            "cs_CZ,65000,1 min 5 s",
            "cs_CZ,45000,45 s"
    })
    void durationsAreLocalised(String language, long millis, String expected) {
        assertEquals(expected, plain(messages.duration(language, Duration.ofMillis(millis))));
    }

    @Test
    @DisplayName("a cooldown message embeds the localised duration")
    void cooldownEmbedsDuration() {
        String language = "cs_CZ";
        Component time = messages.duration(language, Duration.ofSeconds(65));

        assertEquals("Počkej prosím 1 min 5 s, než příkaz použiješ znovu.",
                plain(messages.render(language, MessageKey.ON_COOLDOWN, Placeholder.component("time", time))));
    }

    @Test
    @DisplayName("an unknown language renders in the default one")
    void unknownLanguageUsesDefault() {
        var server = Placeholder.unparsed("server", "lobby");

        assertEquals(plain(messages.render("en_US", MessageKey.CONNECTING, server)),
                plain(messages.render("zz_ZZ", MessageKey.CONNECTING, server)));
    }
}
