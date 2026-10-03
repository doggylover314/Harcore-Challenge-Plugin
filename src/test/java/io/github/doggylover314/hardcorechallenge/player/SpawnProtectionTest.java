package io.github.doggylover314.hardcorechallenge.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpawnProtectionTest {
    private final UUID steve = UUID.randomUUID();
    private long now = 1_000_000L;
    private final SpawnProtection protection = new SpawnProtection(() -> now);

    @Test
    void expiresByTime() {
        protection.grant(steve, 60);
        assertTrue(protection.isProtected(steve));
        assertEquals(60, protection.remainingSeconds(steve));
        now += 59_001L;
        assertTrue(protection.isProtected(steve));
        assertEquals(1, protection.remainingSeconds(steve));
        now += 999L;
        assertFalse(protection.isProtected(steve));
        assertEquals(0, protection.remainingSeconds(steve));
        assertEquals(Set.of(), protection.active());
    }

    @Test
    void zeroSecondsIsOff() {
        protection.grant(steve, 60);
        protection.grant(steve, 0);
        assertFalse(protection.isProtected(steve));
    }

    @Test
    void clearRemoves() {
        protection.grant(steve, 60);
        assertEquals(Set.of(steve), protection.active());
        protection.clear(steve);
        assertFalse(protection.isProtected(steve));
    }
}
