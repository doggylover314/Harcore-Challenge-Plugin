package io.github.doggylover314.hardcorechallenge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SafeSpotTest {
    /** A world of open air with a floor of the given type at y = 63 (so feet at 64 stand on it). */
    private static final class World implements SafeSpot.Terrain {
        private final Map<String, SafeSpot.Cell> cells = new HashMap<>();
        private final SafeSpot.Cell base;
        private final int min;
        private final int max;

        World(SafeSpot.Cell floor, int min, int max) {
            this.min = min;
            this.max = max;
            this.base = SafeSpot.Cell.OPEN;
            if (floor != null) {
                for (int x = -20; x <= 20; x++) {
                    for (int z = -20; z <= 20; z++) {
                        set(x, 63, z, floor);
                    }
                }
            }
        }

        static World ground() {
            return new World(SafeSpot.Cell.SOLID, -64, 320);
        }

        World set(int x, int y, int z, SafeSpot.Cell cell) {
            cells.put(x + "," + y + "," + z, cell);
            return this;
        }

        @Override
        public SafeSpot.Cell cell(int x, int y, int z) {
            if (y < min) {
                return SafeSpot.Cell.OPEN;
            }
            return cells.getOrDefault(x + "," + y + "," + z, base);
        }

        @Override
        public int minY() {
            return min;
        }

        @Override
        public int maxY() {
            return max;
        }
    }

    private static SafeSpot.Block block(int x, int y, int z) {
        return new SafeSpot.Block(x, y, z);
    }

    @Test
    void aSpotOnGroundIsKept() {
        World world = World.ground();
        assertTrue(SafeSpot.isSafe(world, 0, 64, 0));
        assertEquals(Optional.of(block(0, 64, 0)), SafeSpot.find(world, 0, 64, 0));
    }

    @Test
    void aShortDropBelowIsFine() {
        World world = World.ground();
        assertTrue(SafeSpot.isSafe(world, 0, 66, 0), "ground two blocks below");
        assertFalse(SafeSpot.isSafe(world, 0, 67, 0), "a drop of three blocks hurts");
    }

    @Test
    void midAirOverAnAbyssIsNotSafe() {
        World world = new World(null, -64, 320);
        assertFalse(SafeSpot.isSafe(world, 0, 100, 0));
        assertEquals(Optional.empty(), SafeSpot.find(world, 0, 100, 0));
    }

    @Test
    void lavaAtTheFeetOrHeadIsNotSafe() {
        World world = World.ground().set(0, 64, 0, SafeSpot.Cell.HAZARD);
        assertFalse(SafeSpot.isSafe(world, 0, 64, 0));
        World head = World.ground().set(0, 65, 0, SafeSpot.Cell.HAZARD);
        assertFalse(SafeSpot.isSafe(head, 0, 64, 0));
    }

    @Test
    void lavaOrFireOnTheGroundIsNotSafe() {
        World world = World.ground().set(0, 63, 0, SafeSpot.Cell.HAZARD);
        assertFalse(SafeSpot.isSafe(world, 0, 64, 0));
        // Over a lava lake with no ground in between.
        World lake = new World(SafeSpot.Cell.HAZARD, -64, 320);
        assertFalse(SafeSpot.isSafe(lake, 0, 64, 0));
        assertEquals(Optional.empty(), SafeSpot.find(lake, 0, 64, 0));
    }

    /** The spot found must be safe and at least three blocks (so outside the 5x5 square) from the lava. */
    private static void assertMovedAwayFrom(World world, int lavaX, int lavaZ, int fromX, int fromZ) {
        Optional<SafeSpot.Block> found = SafeSpot.find(world, fromX, 64, fromZ);
        assertTrue(found.isPresent());
        SafeSpot.Block spot = found.get();
        assertTrue(SafeSpot.isSafe(world, spot.x(), spot.y(), spot.z()));
        int distance = Math.max(Math.abs(spot.x() - lavaX), Math.abs(spot.z() - lavaZ));
        assertTrue(distance >= 3, "too close to the lava: " + spot);
    }

    @Test
    void aSpotNextToLavaIsNotSafe() {
        World world = World.ground().set(1, 64, 0, SafeSpot.Cell.LAVA);
        assertFalse(SafeSpot.isSafe(world, 0, 64, 0));
        assertFalse(SafeSpot.isSafe(world, -1, 64, 0), "two blocks away is still too close");
        assertTrue(SafeSpot.isSafe(world, -2, 64, 0));
        assertMovedAwayFrom(world, 1, 0, 0, 0);
    }

    @Test
    void aPlayerKilledInALavaSourceIsNotPutNextToIt() {
        World world = World.ground().set(8, 64, 8, SafeSpot.Cell.LAVA);
        assertMovedAwayFrom(world, 8, 8, 7, 7);
        assertMovedAwayFrom(world, 8, 8, 8, 8);
    }

    @Test
    void lavaOnTheDiagonalIsNotSafe() {
        World world = World.ground().set(2, 64, 2, SafeSpot.Cell.LAVA);
        assertFalse(SafeSpot.isSafe(world, 0, 64, 0));
        assertMovedAwayFrom(world, 2, 2, 0, 0);
    }

    @Test
    void lavaBelowOrBesideTheHeadIsNotSafe() {
        assertFalse(SafeSpot.isSafe(World.ground().set(1, 63, 1, SafeSpot.Cell.LAVA), 0, 64, 0), "beside the ground");
        assertFalse(SafeSpot.isSafe(World.ground().set(2, 66, 0, SafeSpot.Cell.LAVA), 0, 64, 0), "above the head, beside");
        assertTrue(SafeSpot.isSafe(World.ground().set(3, 64, 0, SafeSpot.Cell.LAVA), 0, 64, 0), "three blocks away");
    }

    @Test
    void lavaAboveTheHeadMayPourDown() {
        World world = World.ground().set(0, 68, 0, SafeSpot.Cell.LAVA);
        assertFalse(SafeSpot.isSafe(world, 0, 64, 0), "two blocks above the head");
        assertFalse(SafeSpot.isSafe(World.ground().set(0, 69, 0, SafeSpot.Cell.LAVA), 0, 64, 0), "four blocks above the head");
        assertTrue(SafeSpot.isSafe(World.ground().set(0, 70, 0, SafeSpot.Cell.LAVA), 0, 64, 0), "too high to matter");
        assertTrue(SafeSpot.isSafe(World.ground().set(1, 68, 0, SafeSpot.Cell.LAVA), 0, 64, 0), "up high and to the side");
    }

    @Test
    void fireNextToTheSpotIsNotSafe() {
        World world = World.ground().set(1, 64, 0, SafeSpot.Cell.LAVA);
        assertFalse(SafeSpot.isSafe(world, 0, 64, 0));
        assertMovedAwayFrom(world, 1, 0, 0, 0);
    }

    @Test
    void aCactusNextToTheSpotIsFine() {
        World world = World.ground().set(1, 64, 0, SafeSpot.Cell.HAZARD);
        assertTrue(SafeSpot.isSafe(world, 0, 64, 0));
        assertEquals(Optional.of(block(0, 64, 0)), SafeSpot.find(world, 0, 64, 0));
    }

    @Test
    void aSafeSpotIsFoundBesideALavaPool() {
        World world = World.ground();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, 64, z, SafeSpot.Cell.LAVA);
            }
        }
        Optional<SafeSpot.Block> found = SafeSpot.find(world, 0, 64, 0);
        assertTrue(found.isPresent());
        assertTrue(Math.max(Math.abs(found.get().x()), Math.abs(found.get().z())) >= 5, "outside the pool and its margin");
    }

    @Test
    void insideABlockIsNotSafe() {
        World world = World.ground().set(0, 64, 0, SafeSpot.Cell.SOLID);
        assertFalse(SafeSpot.isSafe(world, 0, 64, 0));
        World head = World.ground().set(0, 65, 0, SafeSpot.Cell.SOLID);
        assertFalse(SafeSpot.isSafe(head, 0, 64, 0));
    }

    @Test
    void aSpotInsideTheFloorMovesUp() {
        World world = World.ground();
        assertEquals(Optional.of(block(0, 64, 0)), SafeSpot.find(world, 0, 62, 0));
    }

    @Test
    void aSpotInsideARockMovesToTheNearestAirPocketDownBeforeUp() {
        // Solid rock from y 50 to 80, with air pockets at 58-59 and 66-67.
        World world = new World(null, -64, 320);
        for (int y = 50; y <= 80; y++) {
            world.set(0, y, 0, SafeSpot.Cell.SOLID);
        }
        world.set(0, 58, 0, SafeSpot.Cell.OPEN).set(0, 59, 0, SafeSpot.Cell.OPEN);
        world.set(0, 66, 0, SafeSpot.Cell.OPEN).set(0, 67, 0, SafeSpot.Cell.OPEN);
        assertEquals(Optional.of(block(0, 58, 0)), SafeSpot.find(world, 0, 62, 0));
    }

    @Test
    void theSameColumnIsTriedBeforeTheNextOne() {
        World world = World.ground();
        world.set(0, 64, 0, SafeSpot.Cell.HAZARD);
        // Lava in the spot makes every height above it unsafe too (a fall into it), so the next column is used.
        assertEquals(Optional.of(block(-1, 64, 0)), SafeSpot.find(world, 0, 64, 0));
    }

    @Test
    void aNeighbouringColumnIsUsedWhenTheColumnIsFull() {
        World world = World.ground();
        for (int y = 55; y <= 75; y++) {
            world.set(0, y, 0, SafeSpot.Cell.HAZARD);
        }
        Optional<SafeSpot.Block> found = SafeSpot.find(world, 0, 64, 0);
        assertTrue(found.isPresent());
        SafeSpot.Block spot = found.get();
        assertEquals(1, Math.abs(spot.x()) + Math.abs(spot.z()), "the closest columns first");
        assertEquals(64, spot.y());
        assertTrue(SafeSpot.isSafe(world, spot.x(), spot.y(), spot.z()));
    }

    @Test
    void nothingIsFoundFarFromSafety() {
        World world = World.ground();
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                for (int y = 55; y <= 75; y++) {
                    world.set(x, y, z, SafeSpot.Cell.HAZARD);
                }
            }
        }
        assertEquals(Optional.empty(), SafeSpot.find(world, 0, 64, 0));
    }

    @Test
    void theVoidIsNotSafe() {
        World world = World.ground();
        assertFalse(SafeSpot.isSafe(world, 0, -80, 0), "below the world");
        assertEquals(Optional.empty(), SafeSpot.find(world, 0, -80, 0));
        // Right at the bottom, nothing to stand on.
        World end = new World(null, 0, 256);
        assertFalse(SafeSpot.isSafe(end, 0, 0, 0));
        assertFalse(SafeSpot.isSafe(end, 0, 1, 0));
    }

    @Test
    void theHeadMustFitBelowTheWorldTop() {
        World world = new World(null, -64, 320);
        world.set(0, 316, 0, SafeSpot.Cell.SOLID);
        assertTrue(SafeSpot.isSafe(world, 0, 317, 0));
        assertFalse(SafeSpot.isSafe(world, 0, 319, 0), "head would be out of the world");
    }

    @Test
    void waterIsFineToBeInAndToLandIn() {
        World lake = new World(null, -64, 320);
        for (int y = 55; y <= 63; y++) {
            lake.set(0, y, 0, SafeSpot.Cell.WATER);
        }
        assertTrue(SafeSpot.isSafe(lake, 0, 63, 0), "swimming");
        assertTrue(SafeSpot.isSafe(lake, 0, 64, 0), "on the surface");
        assertTrue(SafeSpot.isSafe(lake, 0, 66, 0), "a short fall into it");
        assertFalse(SafeSpot.isSafe(lake, 0, 68, 0), "too far to fall");
    }

    @Test
    void groundNearTheBottomOfTheWorldIsFound() {
        World end = new World(null, 0, 256);
        end.set(0, 10, 0, SafeSpot.Cell.SOLID);
        assertTrue(SafeSpot.isSafe(end, 0, 11, 0));
        assertEquals(Optional.of(block(0, 11, 0)), SafeSpot.find(end, 0, 8, 0));
    }
}
