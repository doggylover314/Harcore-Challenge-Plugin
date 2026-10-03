package io.github.doggylover314.hardcorechallenge.player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Per-player damage immunity that simply expires. Kept as timestamps in memory (rather than
 * {@code setInvulnerable}) so nothing sticks to a player if the server crashes.
 */
public final class SpawnProtection {
    private final LongSupplier millis;
    private final Map<UUID, Long> expiry = new HashMap<>();

    public SpawnProtection(LongSupplier millis) {
        this.millis = millis;
    }

    /** Protects the player for this many seconds from now; zero or less removes any protection. */
    public void grant(UUID id, int seconds) {
        if (seconds <= 0) {
            expiry.remove(id);
        } else {
            expiry.put(id, millis.getAsLong() + seconds * 1000L);
        }
    }

    public boolean isProtected(UUID id) {
        Long until = expiry.get(id);
        return until != null && until > millis.getAsLong();
    }

    /** Whole seconds left, rounded up; 0 when not protected. */
    public int remainingSeconds(UUID id) {
        Long until = expiry.get(id);
        if (until == null) {
            return 0;
        }
        long left = until - millis.getAsLong();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    public void clear(UUID id) {
        expiry.remove(id);
    }

    public void clear() {
        expiry.clear();
    }

    /** Everyone still protected; drops expired entries on the way. */
    public Set<UUID> active() {
        long now = millis.getAsLong();
        Iterator<Map.Entry<UUID, Long>> iterator = expiry.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
        return Set.copyOf(expiry.keySet());
    }
}
