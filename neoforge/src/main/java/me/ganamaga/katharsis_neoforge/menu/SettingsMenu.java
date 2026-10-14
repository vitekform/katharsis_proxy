package me.ganamaga.katharsis_neoforge.menu;

import com.mojang.logging.LogUtils;
import me.ganamaga.katharsis_neoforge.CompanionMessages;
import me.ganamaga.katharsis_neoforge.proxy.ProxyClient;
import me.ganamaga.katharsis_neoforge.text.Texts;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import org.slf4j.Logger;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * {@code /settings}, and the Discord chat question asked on a player's first
 * join.
 *
 * <p>Both are drawn from one {@code get_settings_menu} reply, so like the
 * language selector they hold no state and no text of their own. Toggling
 * Discord chat redraws the menu in place once the proxy has stored the change,
 * and the proxy confirms it in chat.</p>
 */
public final class SettingsMenu {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ProxyClient client;
    private final CompanionMessages messages;
    private final Supplier<LanguageMenu> languageMenu;

    /** @param languageMenu opened by the language button; supplied late, as it leads back here */
    public SettingsMenu(ProxyClient client, CompanionMessages messages, Supplier<LanguageMenu> languageMenu) {
        this.client = client;
        this.messages = messages;
        this.languageMenu = languageMenu;
    }

    /** Fetches {@code player}'s settings and shows them. Called on the server thread. */
    public void open(ServerPlayer player) {
        fetchThen(player, this::showSettings);
    }

    /**
     * Asks {@code player} whether Discord chat should be shown to them, as the
     * last step of their first join. Called on the server thread.
     */
    public void openDiscordChatPrompt(ServerPlayer player) {
        fetchThen(player, this::showPrompt);
    }

    private void fetchThen(ServerPlayer player, BiConsumer<ServerPlayer, SettingsMenuModel> show) {
        client.requestSettings(player).whenComplete((response, failure) -> {
            if (failure != null || response == null || !response.success()) {
                player.sendSystemMessage(messages.proxyUnreachable());
                LOGGER.warn("Could not fetch the settings for {}: {}", player.getScoreboardName(),
                        failure != null ? failure.toString() : response);
                return;
            }
            if (!player.hasDisconnected()) {
                show.accept(player, SettingsMenuModel.from(response));
            }
        });
    }

    private void showSettings(ServerPlayer player, SettingsMenuModel model) {
        Settings settings = new Settings(model);
        SimpleContainer items = new SimpleContainer(SettingsMenuModel.SIZE);
        settings.draw(items);
        DisplayMenu.open(player, Texts.component(model.strings().title()), items, settings);
    }

    private void showPrompt(ServerPlayer player, SettingsMenuModel model) {
        SimpleContainer items = new SimpleContainer(SettingsMenuModel.SIZE);
        items.setItem(SettingsMenuModel.LEFT_SLOT,
                Icons.describe(Icons.item("minecraft:lime_wool"), model.strings().promptYes(), null, false));
        items.setItem(SettingsMenuModel.RIGHT_SLOT,
                Icons.describe(Icons.item("minecraft:red_wool"), model.strings().promptNo(), null, false));
        // Closing it without answering leaves Discord chat hidden, so only clicks matter.
        DisplayMenu.open(player, Texts.component(model.strings().promptTitle()), items, (menu, viewer, slot) ->
                SettingsMenuModel.answerAt(slot).ifPresent(shown -> {
                    viewer.closeContainer();
                    client.setDiscordChat(viewer, shown).whenComplete((response, failure) -> {
                        if (failure != null || response == null || !response.success()) {
                            reportFailure(viewer, shown, failure);
                        }
                    });
                }));
    }

    private void reportFailure(ServerPlayer player, boolean shown, Throwable failure) {
        player.sendSystemMessage(messages.proxyUnreachable());
        LOGGER.warn("Could not {} Discord chat for {}: {}", shown ? "show" : "hide", player.getScoreboardName(),
                failure != null ? failure.toString() : "the proxy refused");
    }

    /** One open settings menu. */
    private final class Settings implements DisplayMenu.Handler {

        private SettingsMenuModel model;

        /** Whether a change is on its way to the proxy, during which clicks are ignored. */
        private boolean pending;

        Settings(SettingsMenuModel model) {
            this.model = model;
        }

        void draw(SimpleContainer items) {
            String languageHint = model.strings().languageHint().replace("<language>", model.strings().languageName());
            items.setItem(SettingsMenuModel.LEFT_SLOT, Icons.describe(Icons.flag(model.flagModel()),
                    model.strings().languageLabel(), Texts.itemText(languageHint), false));
            items.setItem(SettingsMenuModel.RIGHT_SLOT, Icons.describe(
                    Icons.item(model.discordChat() ? "minecraft:lime_dye" : "minecraft:gray_dye"),
                    model.strings().discordChatLabel(), Texts.itemText(model.discordChatHint()),
                    model.discordChat()));
        }

        @Override
        public void click(DisplayMenu menu, ServerPlayer player, int slot) {
            SettingsMenuModel.buttonAt(slot).ifPresent(button -> {
                switch (button) {
                    case LANGUAGE -> {
                        player.closeContainer();
                        languageMenu.get().open(player, false);
                    }
                    case DISCORD_CHAT -> toggleDiscordChat(menu, player);
                }
            });
        }

        private void toggleDiscordChat(DisplayMenu menu, ServerPlayer player) {
            if (pending) {
                // The last click has not been answered yet.
                return;
            }
            pending = true;
            boolean shown = !model.discordChat();
            client.setDiscordChat(player, shown).whenComplete((response, failure) -> {
                pending = false;
                if (failure != null || response == null || !response.success()) {
                    reportFailure(player, shown, failure);
                    return;
                }
                model = model.withDiscordChat(shown);
                // Redraw only if the menu is still open; otherwise the next
                // /settings fetches the new state anyway.
                if (menu.isOpenFor(player)) {
                    draw(menu.items());
                }
            });
        }
    }
}
