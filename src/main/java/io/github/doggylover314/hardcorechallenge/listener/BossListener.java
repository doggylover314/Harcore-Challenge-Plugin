package io.github.doggylover314.hardcorechallenge.listener;

import io.github.doggylover314.hardcorechallenge.ChallengeManager;
import io.github.doggylover314.hardcorechallenge.core.Boss;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Credits boss deaths to the run. Duplicate reports (e.g. the dragon's phase change and its death
 * event) are ignored by the state machine.
 */
public final class BossListener implements Listener {
    private final ChallengeManager manager;

    public BossListener(ChallengeManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Boss boss = fromType(event.getEntityType());
        if (boss != null) {
            manager.handleBossDeath(boss, event.getEntity().getWorld());
        }
    }

    /**
     * The dragon enters its DYING phase the moment it is killed and plays a ~10 second death
     * animation before it is removed, so this credits the kill at the moment it happened.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonPhase(EnderDragonChangePhaseEvent event) {
        if (event.getNewPhase() == EnderDragon.Phase.DYING) {
            manager.handleBossDeath(Boss.ENDER_DRAGON, event.getEntity().getWorld());
        }
    }

    private static Boss fromType(EntityType type) {
        if (type == EntityType.ENDER_DRAGON) {
            return Boss.ENDER_DRAGON;
        }
        if (type == EntityType.WITHER) {
            return Boss.WITHER;
        }
        if (type == EntityType.ELDER_GUARDIAN) {
            return Boss.ELDER_GUARDIAN;
        }
        if (type == EntityType.WARDEN) {
            return Boss.WARDEN;
        }
        return null;
    }
}
