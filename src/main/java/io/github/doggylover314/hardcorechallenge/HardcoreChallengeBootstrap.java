package io.github.doggylover314.hardcorechallenge;

import io.github.doggylover314.hardcorechallenge.command.HccCommand;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper bootstrapper: creates the plugin instance and registers {@code /hcc} through the lifecycle
 * command registrar, so it is a proper Brigadier command with client-side tab completion.
 */
public final class HardcoreChallengeBootstrap implements PluginBootstrap {
    private volatile HardcoreChallengePlugin plugin;

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        HccCommand.build(() -> plugin == null ? null : plugin.manager()),
                        "Shared-fate hardcore challenge",
                        List.of()));
    }

    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) {
        HardcoreChallengePlugin created = new HardcoreChallengePlugin();
        plugin = created;
        return created;
    }
}
