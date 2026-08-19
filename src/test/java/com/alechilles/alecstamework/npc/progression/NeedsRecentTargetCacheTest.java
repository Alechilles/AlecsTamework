package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class NeedsRecentTargetCacheTest {

    @Test
    void remembersRecentTargetWithinDistance() {
        NeedsRecentTargetCache cache = new NeedsRecentTargetCache(1_000L, 48.0);
        UUID npc = UUID.randomUUID();

        cache.remember(npc, new Vector3d(10.0, 64.0, 10.0), 100L);
        Vector3d target = cache.resolve(npc, new Vector3d(20.0, 64.0, 20.0), 500L);

        assertNotNull(target);
        assertEquals(10.0, target.x, 0.000001);
        assertEquals(64.0, target.y, 0.000001);
        assertEquals(10.0, target.z, 0.000001);
    }

    @Test
    void expiredTargetIsRemoved() {
        NeedsRecentTargetCache cache = new NeedsRecentTargetCache(1_000L, 48.0);
        UUID npc = UUID.randomUUID();

        cache.remember(npc, new Vector3d(10.0, 64.0, 10.0), 100L);
        Vector3d target = cache.resolve(npc, new Vector3d(10.0, 64.0, 12.0), 1_101L);

        assertNull(target);
        assertEquals(0, cache.countForTests());
    }

    @Test
    void distantTargetIsIgnoredButRetainedUntilExpiry() {
        NeedsRecentTargetCache cache = new NeedsRecentTargetCache(1_000L, 16.0);
        UUID npc = UUID.randomUUID();

        cache.remember(npc, new Vector3d(10.0, 64.0, 10.0), 100L);
        Vector3d target = cache.resolve(npc, new Vector3d(40.0, 64.0, 40.0), 500L);

        assertNull(target);
        assertEquals(1, cache.countForTests());
    }

    @Test
    void forgetRemovesMatchingTarget() {
        NeedsRecentTargetCache cache = new NeedsRecentTargetCache(1_000L, 48.0);
        UUID npc = UUID.randomUUID();

        cache.remember(npc, new Vector3d(10.0, 64.0, 10.0), 100L);
        cache.forget(npc, new Vector3d(10.2, 64.0, 10.2));

        assertNull(cache.resolve(npc, new Vector3d(10.0, 64.0, 10.0), 500L));
    }

    @Test
    void recentTargetCacheStaysBounded() {
        NeedsRecentTargetCache cache = new NeedsRecentTargetCache(60_000L, 48.0);
        for (int i = 0; i < NeedsRecentTargetCache.MAX_ENTRIES + 25; i++) {
            cache.remember(
                    new UUID(3L, i),
                    "world-a",
                    new Vector3d(i, 64.0, 0.0),
                    1_000L + i
            );
        }
        assertTrue(cache.countForTests() <= NeedsRecentTargetCache.MAX_ENTRIES);
    }
}
