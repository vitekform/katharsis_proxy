package me.ganamaga.katharsis_proxy.discord;

import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordAccountLinkingTest {

    private static LanguageRegistry registry(Path directory, String defaultLanguage) throws Exception {
        LanguageRegistry registry = new LanguageRegistry(LoggerFactory.getLogger(DiscordAccountLinkingTest.class),
                directory);
        registry.load(defaultLanguage);
        return registry;
    }

    @Test
    @DisplayName("a Discord locale picks the matching language, by region or language alone")
    void languageForLocale(@TempDir Path directory) throws Exception {
        LanguageRegistry registry = registry(directory, "en_US");

        assertEquals("en_US", DiscordAccountLinking.languageFor("en-US", registry));
        assertEquals("en_US", DiscordAccountLinking.languageFor("en-GB", registry), "same language, other region");
        assertEquals("cs_CZ", DiscordAccountLinking.languageFor("cs", registry), "Discord sends Czech as plain cs");
        assertEquals("en_US", DiscordAccountLinking.languageFor("ja", registry), "unknown falls back to the default");
        assertEquals("en_US", DiscordAccountLinking.languageFor(null, registry));
    }

    @Test
    @DisplayName("the code reply renders as Discord markdown in both bundled languages")
    void codeReplyRenders(@TempDir Path directory) throws Exception {
        Messages messages = new Messages(registry(directory, "en_US"), new PlayerLanguageCache());
        var code = Placeholder.unparsed("code", "ABCD2345");
        var hours = Placeholder.unparsed("hours", "6");

        String english = PlainTextComponentSerializer.plainText()
                .serialize(messages.render("en_US", MessageKey.DISCORD_LINK_CODE, code, hours));
        String czech = PlainTextComponentSerializer.plainText()
                .serialize(messages.render("cs_CZ", MessageKey.DISCORD_LINK_CODE, code, hours));

        assertEquals("Your link code is **ABCD2345**. Join the Minecraft server and type `/link ABCD2345` "
                + "within 6 hours. The code works once.", english);
        assertTrue(czech.contains("**ABCD2345**") && czech.contains("`/link ABCD2345`") && czech.contains("6 hodin"),
                czech);
    }

    @Test
    @DisplayName("the command description fits Discord's 100 characters in every bundled language")
    void descriptionFits(@TempDir Path directory) throws Exception {
        LanguageRegistry registry = registry(directory, "en_US");
        Messages messages = new Messages(registry, new PlayerLanguageCache());
        for (String language : registry.languages()) {
            String description = PlainTextComponentSerializer.plainText()
                    .serialize(messages.render(language, MessageKey.DISCORD_LINK_DESCRIPTION));
            assertTrue(!description.isBlank() && description.length() <= 100, language + ": " + description);
        }
    }
}
