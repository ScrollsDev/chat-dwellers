package com.chatdwellers.command;

import com.chatdwellers.action.ChatDwellersActions;
import com.chatdwellers.client.Notify;
import com.chatdwellers.client.PanelOpener;
import com.chatdwellers.config.Config;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// Registered explicitly from ChatDwellers#clientSetup (not via @Mod.EventBusSubscriber).
// Uses the standard (server) RegisterCommandsEvent rather than RegisterClientCommandsEvent —
// the client-command path was registering cleanly but staying unusable in the VH3 modpack.
// In singleplayer the integrated server registers these and syncs them to the client.
public final class ChatDwellersCommand {

    private ChatDwellersCommand() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> tree = Commands.literal("chatdwellers")
            .executes(ChatDwellersCommand::openPanel)
            .then(Commands.literal("help").executes(ChatDwellersCommand::openPanel))
            .then(Commands.literal("status").executes(ChatDwellersCommand::status))
            .then(Commands.literal("purge").executes(ChatDwellersCommand::purge))
            .then(Commands.literal("reconnect").executes(ChatDwellersCommand::reconnect))
            .then(Commands.literal("claim").executes(ChatDwellersCommand::claim))
            .then(Commands.literal("autoclear")
                .then(Commands.literal("on").executes(ctx -> setAutoClear(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> setAutoClear(ctx, false))))
            .then(Commands.literal("blacklist")
                .then(Commands.literal("list").executes(ChatDwellersCommand::blacklistList))
                .then(Commands.literal("remove")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ChatDwellersCommand::blacklistRemove)))
                .then(Commands.literal("add")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> blacklistAdd(ctx, ""))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> blacklistAdd(ctx,
                                StringArgumentType.getString(ctx, "message")))))))
            .then(Commands.literal("on").executes(ChatDwellersCommand::turnOn))
            .then(Commands.literal("off").executes(ChatDwellersCommand::turnOff))
            .then(Commands.literal("cost")
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
                    .executes(ChatDwellersCommand::cost)))
            .then(Commands.literal("simulate")
                .then(Commands.argument("twitch", StringArgumentType.word())
                    .then(Commands.argument("mc", StringArgumentType.word())
                        .executes(ChatDwellersCommand::simulate))));

        LiteralCommandNode<CommandSourceStack> root = event.getDispatcher().register(tree);
        // /cd is a short alias. It needs its OWN executes() so that bare "/cd" (no subcommand)
        // opens the panel — a redirect alone only forwards SUBcommands, leaving bare /cd dead.
        event.getDispatcher().register(Commands.literal("cd")
            .executes(ChatDwellersCommand::openPanel)
            .redirect(root));
        com.chatdwellers.ChatDwellers.LOGGER.info("[ChatDwellers] registered /chatdwellers and /cd commands");
    }

    private static int openPanel(CommandContext<CommandSourceStack> ctx) {
        // Defer to the next client tick (PanelOpener) so the closing chat box doesn't clobber it.
        PanelOpener.request();
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.statusLine());
        return 1;
    }

    private static int purge(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.purge());
        return 1;
    }

    private static int turnOn(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(Config.enabled() ? "Already enabled." : ChatDwellersActions.toggle());
        return 1;
    }

    private static int turnOff(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(Config.enabled() ? ChatDwellersActions.toggle() : "Already disabled.");
        return 1;
    }

    private static int reconnect(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.reconnect());
        return 1;
    }

    private static int cost(CommandContext<CommandSourceStack> ctx) {
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        Notify.toast(ChatDwellersActions.setCost(amount));
        return 1;
    }

    private static int simulate(CommandContext<CommandSourceStack> ctx) {
        String twitch = StringArgumentType.getString(ctx, "twitch");
        String mc = StringArgumentType.getString(ctx, "mc");
        Notify.toast(ChatDwellersActions.simulate(twitch, mc));
        return 1;
    }

    private static int claim(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.claimShown());
        return 1;
    }

    private static int setAutoClear(CommandContext<CommandSourceStack> ctx, boolean on) {
        Notify.toast(ChatDwellersActions.setAutoClear(on));
        return 1;
    }

    private static int blacklistList(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.listBlacklist());
        return 1;
    }

    private static int blacklistRemove(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.removeBlacklist(StringArgumentType.getString(ctx, "name")));
        return 1;
    }

    private static int blacklistAdd(CommandContext<CommandSourceStack> ctx, String message) {
        Notify.toast(ChatDwellersActions.addBlacklist(
            StringArgumentType.getString(ctx, "name"), message));
        return 1;
    }
}
