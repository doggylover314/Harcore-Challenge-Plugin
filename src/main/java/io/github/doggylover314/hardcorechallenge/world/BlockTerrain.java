package io.github.doggylover314.hardcorechallenge.world;

import io.github.doggylover314.hardcorechallenge.core.SafeSpot;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** The blocks of a world as {@link SafeSpot} sees them. */
public record BlockTerrain(World world) implements SafeSpot.Terrain {
    /** Blocks that spread or burn, so they are a danger to anyone close by. */
    private static final Set<Material> LAVAS = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE);
    /** Other blocks that hurt or trap whoever stands in or on them. */
    private static final Set<Material> HAZARDS = Set.of(
            Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW, Material.COBWEB, Material.NETHER_PORTAL, Material.END_PORTAL);

    @Override
    public SafeSpot.Cell cell(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();
        if (LAVAS.contains(type)) {
            return SafeSpot.Cell.LAVA;
        }
        if (HAZARDS.contains(type)) {
            return SafeSpot.Cell.HAZARD;
        }
        if (type == Material.WATER) {
            return SafeSpot.Cell.WATER;
        }
        return block.isPassable() ? SafeSpot.Cell.OPEN : SafeSpot.Cell.SOLID;
    }

    @Override
    public int minY() {
        return world.getMinHeight();
    }

    @Override
    public int maxY() {
        return world.getMaxHeight();
    }
}
