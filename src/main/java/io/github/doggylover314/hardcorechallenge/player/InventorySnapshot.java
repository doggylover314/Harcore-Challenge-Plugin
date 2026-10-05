package io.github.doggylover314.hardcorechallenge.player;

import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * A player's inventory (storage, armor and off hand) and experience as plain data, so it can be saved
 * in state.yml and given back later. Items are Paper's item bytes as Base64.
 */
public final class InventorySnapshot {
    /** Storage 0-35, armor 36-39, off hand 40. */
    private static final int SLOTS = 41;

    private InventorySnapshot() {
    }

    /** The non-empty slots of the inventory, by slot. */
    public static Map<Integer, String> capture(PlayerInventory inventory) {
        Map<Integer, String> items = new TreeMap<>();
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                items.put(slot, Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            }
        }
        return items;
    }

    /** Replaces the player's inventory and experience with the saved ones. Items that cannot be read are skipped. */
    public static void apply(Player player, Map<Integer, String> items, int experience, Logger logger) {
        player.closeInventory();
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SLOTS; slot++) {
            inventory.setItem(slot, read(items.get(slot), player, slot, logger));
        }
        player.setItemOnCursor(null);
        player.setExperienceLevelAndProgress(Math.max(0, experience));
    }

    private static ItemStack read(String encoded, Player player, int slot, Logger logger) {
        if (encoded == null) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not restore the item in slot " + slot + " of " + player.getName(), e);
            return null;
        }
    }
}
