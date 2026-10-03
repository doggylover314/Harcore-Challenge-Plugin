package io.github.doggylover314.hardcorechallenge.player;

import java.util.Iterator;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

/**
 * Wipes a player back to a fresh-spawn state for a new run.
 */
public final class PlayerResetter {
    private PlayerResetter() {
    }

    /** Clears everything a run leaves behind. Does not change game mode or position. */
    public static void wipe(Player player) {
        player.closeInventory();
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
        inventory.setItemInOffHand(null);
        player.setItemOnCursor(null);
        player.getEnderChest().clear();

        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);

        player.clearActivePotionEffects();
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());
        player.setVelocity(new Vector());

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setExhaustion(0f);

        // A bed or anchor from the old world is meaningless in the new one.
        player.setRespawnLocation(null, true);

        revokeAdvancements(player);
    }

    /** Empties the inventory, cursor and experience (not the ender chest). Used when a player is out of the run. */
    public static void clearItems(Player player) {
        player.closeInventory();
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
        inventory.setItemInOffHand(null);
        player.setItemOnCursor(null);
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);
    }

    /** Full health and food, no effects or fire, and survival mode. Keeps the inventory. */
    public static void revive(Player player) {
        player.clearActivePotionEffects();
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());
        player.setVelocity(new Vector());
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setExhaustion(0f);
        player.setGameMode(GameMode.SURVIVAL);
    }

    /** Wipes the player and puts them in survival, ready to play. */
    public static void prepareForRun(Player player) {
        wipe(player);
        player.setGameMode(GameMode.SURVIVAL);
    }

    private static void revokeAdvancements(Player player) {
        Iterator<Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            AdvancementProgress progress = player.getAdvancementProgress(iterator.next());
            for (String criterion : progress.getAwardedCriteria()) {
                progress.revokeCriteria(criterion);
            }
        }
    }
}
