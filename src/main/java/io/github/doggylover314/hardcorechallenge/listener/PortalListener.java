package io.github.doggylover314.hardcorechallenge.listener;

import io.github.doggylover314.hardcorechallenge.ChallengeManager;
import io.github.doggylover314.hardcorechallenge.world.RunWorlds;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Keeps portals inside the current run: overworld ⇄ {@code _nether} and overworld/nether →
 * {@code _the_end} → overworld. The destination is set explicitly rather than relying on how the
 * server links custom worlds; for nether portals the server then finds or builds a portal near it.
 */
public final class PortalListener implements Listener {
    private static final double NETHER_SCALE = 8.0;

    private final ChallengeManager manager;

    public PortalListener(ChallengeManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        PortalType type = switch (event.getCause()) {
            case NETHER_PORTAL -> PortalType.NETHER;
            case END_PORTAL -> PortalType.ENDER;
            default -> null;
        };
        if (type == null) {
            return;
        }
        destination(event.getFrom(), type, event.getPlayer()).ifPresent(event::setTo);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        PortalType type = event.getPortalType();
        if (type != PortalType.NETHER && type != PortalType.ENDER) {
            return;
        }
        destination(event.getFrom(), type, null).ifPresent(event::setTo);
    }

    /** Leaving the End (after the credits) respawns the player; keep them in the run's overworld. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Optional<RunWorlds> worlds = manager.currentWorlds();
        if (worlds.isEmpty() || !manager.machine().isActive()) {
            return;
        }
        RunWorlds run = worlds.get();
        if (!run.contains(event.getPlayer().getWorld()) || run.contains(event.getRespawnLocation().getWorld())) {
            return;
        }
        event.setRespawnLocation(overworldArrival(run, event.getPlayer()));
    }

    private Optional<Location> destination(Location from, PortalType type, Player player) {
        Optional<RunWorlds> worlds = manager.currentWorlds();
        if (worlds.isEmpty() || from.getWorld() == null) {
            return Optional.empty();
        }
        RunWorlds run = worlds.get();
        World source = from.getWorld();
        if (!run.contains(source)) {
            return Optional.empty();
        }

        if (type == PortalType.NETHER) {
            if (source.equals(run.overworld())) {
                return Optional.of(scaled(from, run.nether(), 1.0 / NETHER_SCALE));
            }
            if (source.equals(run.nether())) {
                return Optional.of(scaled(from, run.overworld(), NETHER_SCALE));
            }
            return Optional.empty();
        }

        // End portal
        if (source.equals(run.end())) {
            return Optional.of(overworldArrival(run, player));
        }
        return Optional.of(endPlatform(run.end()));
    }

    private static Location scaled(Location from, World target, double factor) {
        double x = from.getX() * factor;
        double z = from.getZ() * factor;
        double y = Math.clamp(from.getY(), target.getMinHeight() + 1, target.getMaxHeight() - 2);
        return new Location(target, x, y, z, from.getYaw(), from.getPitch());
    }

    private static Location overworldArrival(RunWorlds run, Player player) {
        if (player != null) {
            Location respawn = player.getRespawnLocation();
            if (respawn != null && run.overworld().equals(respawn.getWorld())) {
                return respawn;
            }
        }
        return run.spawn();
    }

    /**
     * The vanilla arrival point in the End: a 5x5 obsidian platform at 100, 48, 0 with air above.
     * Built here so arrival is safe whether or not the server generates it for a plugin-created End.
     */
    private static Location endPlatform(World end) {
        int cx = 100;
        int cy = 48;
        int cz = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                end.getBlockAt(cx + dx, cy, cz + dz).setType(Material.OBSIDIAN, false);
                for (int dy = 1; dy <= 3; dy++) {
                    Block above = end.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!above.getType().isAir()) {
                        above.setType(Material.AIR, false);
                    }
                }
            }
        }
        return new Location(end, cx + 0.5, cy + 1, cz + 0.5, 90f, 0f);
    }
}
