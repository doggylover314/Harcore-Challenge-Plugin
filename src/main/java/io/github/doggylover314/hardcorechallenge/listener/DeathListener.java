package io.github.doggylover314.hardcorechallenge.listener;

import io.github.doggylover314.hardcorechallenge.ChallengeManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class DeathListener implements Listener {
    private final ChallengeManager manager;

    public DeathListener(ChallengeManager manager) {
        this.manager = manager;
    }

    // HIGHEST so other plugins (e.g. revive mechanics) get their say first; we cancel the event.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        manager.handleDeath(event);
    }
}
