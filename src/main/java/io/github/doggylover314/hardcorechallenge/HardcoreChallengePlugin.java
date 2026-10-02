package io.github.doggylover314.hardcorechallenge;

import io.github.doggylover314.hardcorechallenge.config.Messages;
import io.github.doggylover314.hardcorechallenge.config.Settings;
import io.github.doggylover314.hardcorechallenge.listener.BossListener;
import io.github.doggylover314.hardcorechallenge.listener.ConnectionListener;
import io.github.doggylover314.hardcorechallenge.listener.DeathListener;
import io.github.doggylover314.hardcorechallenge.listener.PortalListener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class HardcoreChallengePlugin extends JavaPlugin {
    public static final String PERMISSION_ADMIN = "hardcorechallenge.admin";
    public static final String PERMISSION_PLAY = "hardcorechallenge.play";

    private ChallengeManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Missing keys fall back to the bundled defaults.
        getConfig().options().copyDefaults(true);

        Settings settings = Settings.load(getConfig(), getLogger());
        Messages messages = new Messages(getConfig(), getLogger());
        ChallengeManager created = new ChallengeManager(this, settings, messages);
        created.enable();
        manager = created;

        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(new DeathListener(created), this);
        plugins.registerEvents(new BossListener(created), this);
        plugins.registerEvents(new ConnectionListener(created), this);
        plugins.registerEvents(new PortalListener(created), this);
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.disable();
            manager = null;
        }
    }

    /** The running manager, or {@code null} while the plugin is not enabled. */
    public ChallengeManager manager() {
        return manager;
    }
}
