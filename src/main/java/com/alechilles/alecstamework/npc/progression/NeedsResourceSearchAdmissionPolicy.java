package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.performance.RuntimePressureLevel;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Bounds cold needs-resource searches according to current runtime pressure.
 */
public final class NeedsResourceSearchAdmissionPolicy {
    private static final long DEFERRED_TTL_BASE_MS = 100L;
    private static final long DEFERRED_TTL_RANGE_MS = 201L;

    /**
     * Returns whether a cold search may run on the supplied world tick.
     *
     * <p>Higher pressure admits searches less often so a burst spreads over later ticks.
     */
    public boolean maySearch(@Nonnull RuntimePressureLevel level, long worldTick) {
        int divisor = switch (level) {
            case NORMAL -> 1;
            case WARM -> 2;
            case HOT -> 4;
            case EMERGENCY -> 8;
        };
        return Math.floorMod(worldTick, divisor) == 0;
    }

    /**
     * Returns a stable deferral delay for the supplied NPC in the 100-300 millisecond range.
     */
    public long deferredTtlMs(@Nonnull UUID npcId) {
        long mixedUuidBits = npcId.getMostSignificantBits()
                ^ Long.rotateLeft(npcId.getLeastSignificantBits(), 21);
        return DEFERRED_TTL_BASE_MS + Math.floorMod(mixedUuidBits, DEFERRED_TTL_RANGE_MS);
    }
}
