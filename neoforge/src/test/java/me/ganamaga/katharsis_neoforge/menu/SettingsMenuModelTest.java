package me.ganamaga.katharsis_neoforge.menu;

import me.ganamaga.katharsis_neoforge.protocol.ProxyResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsMenuModelTest {

    private static SettingsMenuModel model(String language, boolean discordChat) {
        return SettingsMenuModel.from(new ProxyResponse("get_settings_menu", "r1", "uuid", true, language, "",
                null, "", new ProxyResponse.Settings("title", "Language", "Currently <language>", "Čeština",
                discordChat, "Discord chat", "shown", "hidden", "question", "yes", "no")));
    }

    @Test
    @DisplayName("the Discord chat hint follows the current state")
    void hintFollowsState() {
        SettingsMenuModel hidden = model("cs_CZ", false);
        assertFalse(hidden.discordChat());
        assertEquals("hidden", hidden.discordChatHint());

        SettingsMenuModel shown = hidden.withDiscordChat(true);
        assertTrue(shown.discordChat());
        assertEquals("shown", shown.discordChatHint());
        assertFalse(hidden.discordChat(), "the original is left alone");
    }

    @Test
    @DisplayName("the language button shows the current language's flag")
    void flag() {
        assertEquals(Optional.of("flags_cz"), model("cs_CZ", false).flagModel());
        assertEquals(Optional.empty(), model("cs", false).flagModel());
    }

    @Test
    @DisplayName("each button and answer sits in its own slot, and nothing else is clickable")
    void slots() {
        assertEquals(Optional.of(SettingsMenuModel.Button.LANGUAGE),
                SettingsMenuModel.buttonAt(SettingsMenuModel.LEFT_SLOT));
        assertEquals(Optional.of(SettingsMenuModel.Button.DISCORD_CHAT),
                SettingsMenuModel.buttonAt(SettingsMenuModel.RIGHT_SLOT));
        assertEquals(Optional.of(true), SettingsMenuModel.answerAt(SettingsMenuModel.LEFT_SLOT));
        assertEquals(Optional.of(false), SettingsMenuModel.answerAt(SettingsMenuModel.RIGHT_SLOT));

        for (int slot = 0; slot < SettingsMenuModel.SIZE; slot++) {
            if (slot != SettingsMenuModel.LEFT_SLOT && slot != SettingsMenuModel.RIGHT_SLOT) {
                assertTrue(SettingsMenuModel.buttonAt(slot).isEmpty());
                assertTrue(SettingsMenuModel.answerAt(slot).isEmpty());
            }
        }
        assertEquals(0, SettingsMenuModel.SIZE % 9);
    }

    @Test
    @DisplayName("a reply without settings is refused")
    void requiresSettings() {
        ProxyResponse plain = new ProxyResponse("get_language", "r1", "uuid", true, "en_US", "", null, "", null);
        assertThrows(IllegalStateException.class, () -> SettingsMenuModel.from(plain));
    }
}
