package io.github.doggylover314.hardcorechallenge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.doggylover314.hardcorechallenge.ChallengeManager;
import io.github.doggylover314.hardcorechallenge.HardcoreChallengePlugin;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/**
 * The {@code /hcc} command tree. Brigadier provides tab completion for every branch.
 */
public final class HccCommand {
    private static final int DEFAULT_HISTORY = 5;
    private static final int MAX_HISTORY = 50;

    private HccCommand() {
    }

    /**
     * @param manager supplies the manager once the plugin is enabled (commands are registered during bootstrap)
     */
    public static LiteralCommandNode<CommandSourceStack> build(Supplier<ChallengeManager> manager) {
        return Commands.literal("hcc")
                .requires(source -> canPlay(source.getSender()))
                .executes(ctx -> run(ctx, manager, (m, sender) -> {
                    if (sender.hasPermission(HardcoreChallengePlugin.PERMISSION_ADMIN)) {
                        sender.sendMessage(m.messages().plain("help"));
                    } else {
                        m.sendStatus(sender);
                    }
                }))
                .then(Commands.literal("start")
                        .requires(HccCommand::isAdmin)
                        .executes(ctx -> run(ctx, manager, ChallengeManager::start)))
                .then(Commands.literal("reset")
                        .requires(HccCommand::isAdmin)
                        .executes(ctx -> run(ctx, manager, (m, sender) -> m.forceReset(sender, null)))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, manager, (m, sender) ->
                                        m.forceReset(sender, StringArgumentType.getString(ctx, "reason"))))))
                .then(Commands.literal("stop")
                        .requires(HccCommand::isAdmin)
                        .executes(ctx -> run(ctx, manager, ChallengeManager::stop)))
                .then(Commands.literal("status")
                        .executes(ctx -> run(ctx, manager, ChallengeManager::sendStatus)))
                .then(Commands.literal("participants")
                        .requires(HccCommand::isAdmin)
                        .executes(ctx -> run(ctx, manager, ChallengeManager::listParticipants))
                        .then(Commands.literal("list")
                                .executes(ctx -> run(ctx, manager, ChallengeManager::listParticipants)))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggest(builder, manager, true))
                                        .executes(ctx -> run(ctx, manager, (m, sender) ->
                                                m.addParticipant(sender, StringArgumentType.getString(ctx, "player"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggest(builder, manager, false))
                                        .executes(ctx -> run(ctx, manager, (m, sender) ->
                                                m.removeParticipant(sender, StringArgumentType.getString(ctx, "player")))))))
                .then(Commands.literal("history")
                        .requires(HccCommand::isAdmin)
                        .executes(ctx -> run(ctx, manager, (m, sender) -> m.sendHistory(sender, DEFAULT_HISTORY)))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, MAX_HISTORY))
                                .executes(ctx -> run(ctx, manager, (m, sender) ->
                                        m.sendHistory(sender, IntegerArgumentType.getInteger(ctx, "n"))))))
                .then(Commands.literal("reload")
                        .requires(HccCommand::isAdmin)
                        .executes(ctx -> run(ctx, manager, (m, sender) -> {
                            m.reload();
                            sender.sendMessage(m.messages().chat("reloaded"));
                        })))
                .build();
    }

    private static boolean isAdmin(CommandSourceStack source) {
        return source.getSender().hasPermission(HardcoreChallengePlugin.PERMISSION_ADMIN);
    }

    private static boolean canPlay(CommandSender sender) {
        return sender.hasPermission(HardcoreChallengePlugin.PERMISSION_PLAY)
                || sender.hasPermission(HardcoreChallengePlugin.PERMISSION_ADMIN);
    }

    private interface Action {
        void run(ChallengeManager manager, CommandSender sender);
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Supplier<ChallengeManager> supplier, Action action) {
        CommandSender sender = ctx.getSource().getSender();
        ChallengeManager manager = supplier.get();
        if (manager == null) {
            sender.sendMessage(Component.text("HardcoreChallenge is not enabled.", NamedTextColor.RED));
            return 0;
        }
        action.run(manager, sender);
        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, Supplier<ChallengeManager> supplier, boolean adding) {
        ChallengeManager manager = supplier.get();
        if (manager != null) {
            String typed = builder.getRemainingLowerCase();
            for (String name : manager.participantNames(adding)) {
                if (name.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(name);
                }
            }
        }
        return builder.buildFuture();
    }
}
