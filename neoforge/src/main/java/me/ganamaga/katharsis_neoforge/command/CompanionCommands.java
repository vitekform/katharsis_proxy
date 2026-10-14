package me.ganamaga.katharsis_neoforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import me.ganamaga.katharsis_neoforge.CompanionMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.function.Consumer;

/**
 * {@code /language} (and {@code /lang}) and {@code /settings}, open to every
 * player.
 *
 * <p>These are the only ones on the network: the proxy deliberately registers
 * none, so there is no second implementation to shadow or be shadowed by.</p>
 */
public final class CompanionCommands {

    private final CompanionMessages messages;
    private final Consumer<ServerPlayer> openLanguage;
    private final Consumer<ServerPlayer> openSettings;

    public CompanionCommands(CompanionMessages messages, Consumer<ServerPlayer> openLanguage,
                             Consumer<ServerPlayer> openSettings) {
        this.messages = messages;
        this.openLanguage = openLanguage;
        this.openSettings = openSettings;
    }

    public void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        // An alias is its own literal: brigadier's redirect does not run a node without arguments.
        for (String name : new String[] {"language", "lang"}) {
            dispatcher.register(Commands.literal(name).executes(context -> run(context, openLanguage)));
        }
        dispatcher.register(Commands.literal("settings").executes(context -> run(context, openSettings)));
    }

    private int run(CommandContext<CommandSourceStack> context, Consumer<ServerPlayer> open) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(messages.playersOnly());
            return 0;
        }
        open.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
