package io.github.doggylover314.hardcorechallenge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.doggylover314.hardcorechallenge.ChallengeManager;
import io.github.doggylover314.hardcorechallenge.HardcoreChallengePlugin;
import io.github.doggylover314.hardcorechallenge.core.Outcome;
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
                        .executes(ctx -> run(ctx, manager, ChallengeManager::start))
                        .then(Commands.literal("replay")
                                .then(Commands.argument("run", IntegerArgumentType.integer(1))
                                        .suggests((ctx, builder) -> suggestRuns(builder, manager))
                                        .executes(ctx -> run(ctx, manager, (m, sender) ->
                                                m.startReplay(sender, IntegerArgumentType.getInteger(ctx, "run"))))))
                        .then(Commands.literal("seed")
                                .then(Commands.argument("seed", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, manager, (m, sender) ->
                                                m.startWithSeed(sender, StringArgumentType.getString(ctx, "seed")))))))
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
                .then(runsNode("runs", manager))
                .then(runsNode("history", manager))
                .then(Commands.literal("run")
                        .requires(HccCommand::isAdmin)
                        .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestRuns(builder, manager))
                                .executes(ctx -> run(ctx, manager, (m, sender) -> m.sendRun(sender, number(ctx))))
                                .then(Commands.literal("timeline")
                                        .executes(ctx -> run(ctx, manager, (m, sender) -> m.sendTimeline(sender, number(ctx), 1)))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> run(ctx, manager, (m, sender) ->
                                                        m.sendTimeline(sender, number(ctx), IntegerArgumentType.getInteger(ctx, "page"))))))
                                .then(Commands.literal("delete")
                                        .executes(ctx -> run(ctx, manager, (m, sender) -> m.requestDelete(sender, number(ctx))))
                                        .then(Commands.literal("confirm")
                                                .executes(ctx -> run(ctx, manager, (m, sender) -> m.confirmDelete(sender, number(ctx))))))))
                .then(Commands.literal("reload")
                        .requires(HccCommand::isAdmin)
                        .executes(ctx -> run(ctx, manager, (m, sender) -> {
                            m.reload();
                            sender.sendMessage(m.messages().chat("reloaded"));
                        })))
                .build();
    }

    /** /hcc runs (and its alias /hcc history): every run, filterable by outcome or player, paged. */
    private static LiteralArgumentBuilder<CommandSourceStack> runsNode(String label, Supplier<ChallengeManager> manager) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(label)
                .requires(HccCommand::isAdmin)
                .executes(ctx -> run(ctx, manager, (m, sender) -> m.sendRuns(sender, null, null, 1)))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> run(ctx, manager, (m, sender) -> m.sendRuns(sender, null, null, page(ctx)))));
        for (Outcome outcome : Outcome.values()) {
            node.then(Commands.literal(ChallengeManager.outcomeFilterName(outcome))
                    .executes(ctx -> run(ctx, manager, (m, sender) -> m.sendRuns(sender, outcome, null, 1)))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> run(ctx, manager, (m, sender) -> m.sendRuns(sender, outcome, null, page(ctx))))));
        }
        node.then(Commands.literal("player")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestNames(builder, manager))
                        .executes(ctx -> run(ctx, manager, (m, sender) ->
                                m.sendRuns(sender, null, StringArgumentType.getString(ctx, "name"), 1)))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> run(ctx, manager, (m, sender) ->
                                        m.sendRuns(sender, null, StringArgumentType.getString(ctx, "name"), page(ctx)))))));
        return node;
    }

    private static int number(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "number");
    }

    private static int page(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "page");
    }

    private static CompletableFuture<Suggestions> suggestRuns(SuggestionsBuilder builder, Supplier<ChallengeManager> supplier) {
        ChallengeManager manager = supplier.get();
        if (manager != null) {
            for (int number : manager.runNumbers()) {
                String text = String.valueOf(number);
                if (text.startsWith(builder.getRemaining())) {
                    builder.suggest(number);
                }
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestNames(SuggestionsBuilder builder, Supplier<ChallengeManager> supplier) {
        ChallengeManager manager = supplier.get();
        if (manager != null) {
            String typed = builder.getRemainingLowerCase();
            for (String name : manager.knownPlayerNames()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(name);
                }
            }
        }
        return builder.buildFuture();
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
}
