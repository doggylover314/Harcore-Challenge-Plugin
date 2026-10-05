package io.github.doggylover314.hardcorechallenge.listener;

import com.destroystokyo.paper.event.entity.ExperienceOrbMergeEvent;
import io.github.doggylover314.hardcorechallenge.ChallengeManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Keeps what a death dropped from merging into other items or orbs, so it can still be found and removed
 * when the run is continued, and removes
 * the ones that load later than that.
 */
public final class DropListener implements Listener {
    private final ChallengeManager manager;

    public DropListener(ChallengeManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        if (!manager.sameDeathDrop(event.getEntity(), event.getTarget())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOrbMerge(ExperienceOrbMergeEvent event) {
        if (!manager.sameDeathDrop(event.getMergeSource(), event.getMergeTarget())) {
            event.setCancelled(true);
        }
    }

    // Entities load separately from (and after) their chunk, so a continue can't see them in time.
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        manager.removeLoadedDeathDrops(event.getWorld(), event.getEntities());
    }
}
