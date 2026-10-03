package io.github.doggylover314.hardcorechallenge.tracking;

import io.github.doggylover314.hardcorechallenge.ChallengeManager;
import io.github.doggylover314.hardcorechallenge.core.Boss;
import io.github.doggylover314.hardcorechallenge.core.RunLog;
import io.github.doggylover314.hardcorechallenge.core.RunPhase;
import io.github.doggylover314.hardcorechallenge.core.TimelineEvent;
import io.github.doggylover314.hardcorechallenge.listener.BossListener;
import io.github.doggylover314.hardcorechallenge.world.RunWorlds;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.math.Position;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.ItemStack;

/**
 * Collects per-player stats and timeline milestones for the run being played.
 */
public final class RunTracker implements Listener {
    private static final List<Statistic> DISTANCE_STATS = List.of(
            Statistic.WALK_ONE_CM, Statistic.SPRINT_ONE_CM, Statistic.CROUCH_ONE_CM, Statistic.SWIM_ONE_CM,
            Statistic.WALK_ON_WATER_ONE_CM, Statistic.WALK_UNDER_WATER_ONE_CM, Statistic.CLIMB_ONE_CM,
            Statistic.FALL_ONE_CM, Statistic.FLY_ONE_CM, Statistic.AVIATE_ONE_CM, Statistic.BOAT_ONE_CM,
            Statistic.MINECART_ONE_CM, Statistic.HORSE_ONE_CM, Statistic.PIG_ONE_CM, Statistic.STRIDER_ONE_CM,
            Statistic.HAPPY_GHAST_ONE_CM, Statistic.NAUTILUS_ONE_CM);

    /** Items whose first pickup in a run is a timeline milestone. */
    public static final Map<Material, String> MILESTONE_ITEMS = Map.of(
            Material.IRON_INGOT, "iron_ingot",
            Material.DIAMOND, "diamond",
            Material.BLAZE_ROD, "blaze_rod",
            Material.ENDER_EYE, "ender_eye");

    private static final Map<String, Structure> STRUCTURES = Map.of(
            "stronghold", Structure.STRONGHOLD,
            "monument", Structure.MONUMENT,
            "ancient_city", Structure.ANCIENT_CITY);

    /** Longest gap counted as play time between two samples, so a server freeze isn't counted. */
    private static final long MAX_SAMPLE_GAP_MILLIS = 5_000L;

    private final ChallengeManager manager;
    private final Map<UUID, Long> distanceBaseline = new HashMap<>();
    private final Map<UUID, Long> lastSample = new HashMap<>();
    private int samples;

    public RunTracker(ChallengeManager manager) {
        this.manager = manager;
    }

    /** Forgets per-player baselines, e.g. when a new run starts. */
    public void reset() {
        distanceBaseline.clear();
        lastSample.clear();
    }

    public void forget(UUID id) {
        distanceBaseline.remove(id);
        lastSample.remove(id);
    }

    private RunLog live() {
        return manager.machine().phase() == RunPhase.RUNNING ? manager.liveRun() : null;
    }

    private boolean tracked(Player player) {
        return player != null && manager.roster().isActive(player.getUniqueId()) && manager.isRunWorld(player.getWorld());
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        RunLog live = live();
        Player attacker = attacker(event);
        if (live == null || !tracked(attacker)) {
            return;
        }
        Entity victim = event.getEntity() instanceof ComplexEntityPart part ? part.getParent() : event.getEntity();
        if (victim instanceof Player || victim instanceof ArmorStand || !(victim instanceof LivingEntity living)) {
            return;
        }
        // Count only damage that actually landed, not overkill.
        double dealt = Math.min(event.getFinalDamage(), living.getHealth() + living.getAbsorptionAmount());
        // Only bosses this run is scored against count as boss damage.
        Boss boss = BossListener.fromType(victim.getType());
        if (boss != null && !manager.machine().trackedBosses().contains(boss)) {
            boss = null;
        }
        live.stats(attacker.getUniqueId(), attacker.getName()).addDamage(dealt, boss != null);
        if (boss != null && live.first("fight:" + boss.id())) {
            manager.logEvent(TimelineEvent.Type.BOSS_FIGHT_STARTED, attacker.getName(), boss.id());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        RunLog live = live();
        if (live == null || event.getEntity() instanceof Player) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (tracked(killer)) {
            live.stats(killer.getUniqueId(), killer.getName()).addMobKill();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        RunLog live = live();
        ItemStack item = event.getNewItemStack();
        if (live == null || item == null || !tracked(event.getPlayer())) {
            return;
        }
        String key = MILESTONE_ITEMS.get(item.getType());
        if (key != null && live.first("item:" + key)) {
            manager.logEvent(TimelineEvent.Type.MILESTONE, event.getPlayer().getName(), key);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        RunLog live = live();
        Player player = event.getPlayer();
        if (live == null || !tracked(player)) {
            return;
        }
        RunWorlds worlds = manager.currentWorlds().orElse(null);
        if (worlds == null) {
            return;
        }
        String dimension = null;
        if (player.getWorld().equals(worlds.nether())) {
            dimension = "the_nether";
        } else if (player.getWorld().equals(worlds.end())) {
            dimension = "the_end";
        }
        if (dimension != null && live.first("dim:" + dimension)) {
            manager.logEvent(TimelineEvent.Type.DIMENSION_FIRST, player.getName(), dimension);
        }
    }

    // ----------------------------------------------------------------- sampling

    /** Called once a second: distance, play time and structure discovery. */
    public void sample() {
        sample(live());
    }

    /**
     * Samples into a specific log, which may no longer be the live one. Used when a run ends, after
     * the phase has already changed.
     */
    public void sample(RunLog live) {
        RunWorlds worlds = manager.currentWorlds().orElse(null);
        long now = System.currentTimeMillis();
        boolean checkStructures = (samples++ & 1) == 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            long distance = totalDistance(player);
            Long baseline = distanceBaseline.put(id, distance);
            Long previous = lastSample.put(id, now);
            if (live == null || !tracked(player) || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            var stats = live.stats(id, player.getName());
            if (baseline != null) {
                stats.addDistance(distance - baseline);
            }
            if (previous != null && manager.machine().clockRunning()) {
                stats.addTimePlayed(Math.min(MAX_SAMPLE_GAP_MILLIS, now - previous));
            }
            if (checkStructures && worlds != null && player.getWorld().equals(worlds.overworld())) {
                checkStructures(live, player);
            }
        }
    }

    private void checkStructures(RunLog live, Player player) {
        World world = player.getWorld();
        Position position = Position.block(player.getLocation());
        for (Map.Entry<String, Structure> entry : STRUCTURES.entrySet()) {
            String key = "structure:" + entry.getKey();
            if (!live.hasFirst(key) && world.hasStructureAt(position, entry.getValue()) && live.first(key)) {
                manager.logEvent(TimelineEvent.Type.STRUCTURE_FIRST, player.getName(), entry.getKey());
            }
        }
    }

    private static long totalDistance(Player player) {
        long total = 0;
        for (Statistic statistic : DISTANCE_STATS) {
            total += player.getStatistic(statistic);
        }
        return total;
    }

    /**
     * The player responsible for a hit: whoever the damage source names as the cause (covers end
     * crystals or TNT set off by a player, area effect clouds, thrown potions), else the damager itself.
     * Fire and burn ticks have no causing entity, so they stay uncounted.
     */
    private static Player attacker(EntityDamageByEntityEvent event) {
        Player player = asPlayer(event.getDamageSource().getCausingEntity());
        return player != null ? player : asPlayer(event.getDamager());
    }

    private static Player asPlayer(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            return source;
        }
        if (entity instanceof Tameable pet && pet.getOwnerUniqueId() != null) {
            return Bukkit.getPlayer(pet.getOwnerUniqueId());
        }
        return null;
    }
}
