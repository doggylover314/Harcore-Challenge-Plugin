package io.github.doggylover314.hardcorechallenge.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Proxy;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/** Checks the shape of the /hcc seeds tree. No manager is available, so commands only get as far as running. */
class HccCommandTest {
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

    HccCommandTest() {
        dispatcher.getRoot().addChild(HccCommand.build(() -> null));
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type.isPrimitive()) {
            return type == long.class ? 0L : type == double.class ? 0.0 : type == float.class ? 0f : 0;
        }
        return null;
    }

    private static CommandSourceStack source(boolean admin) {
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(HccCommandTest.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, args) ->
                        method.getName().equals("hasPermission") ? admin : defaultFor(method.getReturnType()));
        return (CommandSourceStack) Proxy.newProxyInstance(HccCommandTest.class.getClassLoader(),
                new Class<?>[] {CommandSourceStack.class}, (proxy, method, args) ->
                        method.getName().equals("getSender") ? sender : defaultFor(method.getReturnType()));
    }

    private int run(String command) throws CommandSyntaxException {
        return dispatcher.execute(command, source(true));
    }

    private List<String> suggestions(String command) {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(command, source(true))).join()
                .getList().stream().map(s -> s.getText()).toList();
    }

    @Test
    void everySeedsCommandIsReachable() throws CommandSyntaxException {
        // 0 = the manager is missing, which is as far as these can go here.
        for (String command : new String[] {"hcc seeds", "hcc seeds add 12345", "hcc seeds add my text seed",
                "hcc seeds add -5", "hcc seeds remove 2", "hcc seeds clear", "hcc seeds mode once", "hcc seeds mode cycle"}) {
            assertEquals(0, run(command), command);
        }
    }

    @Test
    void incompleteOrInvalidSeedsCommandsAreRejected() {
        for (String command : new String[] {"hcc seeds add", "hcc seeds remove", "hcc seeds remove 0", "hcc seeds remove -1",
                "hcc seeds remove first", "hcc seeds mode", "hcc seeds mode forever", "hcc seeds nothing"}) {
            assertThrows(CommandSyntaxException.class, () -> run(command), command);
        }
    }

    @Test
    void textSeedsMayContainSpaces() {
        var parsed = dispatcher.parse("hcc seeds add my text seed", source(true));
        assertEquals("my text seed", parsed.getContext().getArguments().get("seed").getResult());
    }

    @Test
    void theSubcommandsAreSuggested() {
        assertEquals(List.of("add", "clear", "mode", "remove"), suggestions("hcc seeds "));
        assertEquals(List.of("cycle", "once"), suggestions("hcc seeds mode "));
        assertEquals(List.of("once"), suggestions("hcc seeds mode o"));
    }

    @Test
    void playersWithoutAdminCannotUseSeeds() {
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("hcc seeds", source(false)));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("hcc seeds add 1", source(false)));
    }
}
