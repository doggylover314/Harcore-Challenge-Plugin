package io.github.doggylover314.hardcorechallenge.listener;

import io.github.doggylover314.hardcorechallenge.ChallengeManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Cancels damage to players who just entered a run (spawn protection).
 */
public final class ProtectionListener implements Listener {
    private final ChallengeManager manager;

    public ProtectionListener(ChallengeManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !manager.isSpawnProtected(player.getUniqueId())) {
            return;
        }
        // Leave the void and /kill alone so nobody gets stuck or can't be removed by an admin.
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL
                || cause == EntityDamageEvent.DamageCause.SUICIDE) {
            return;
        }
        event.setCancelled(true);
    }
}
