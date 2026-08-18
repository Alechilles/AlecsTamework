package com.alechilles.alecstamework.items;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetQueryCacheTest {
    private static final UUID PLAYER_UUID = UUID.fromString("881966df-50f3-4a66-a965-180d4d3bdd16");
    private static final UUID FIRST_TARGET_UUID = UUID.fromString("637a8907-2e60-40e5-961a-da57dd7df659");
    private static final UUID SECOND_TARGET_UUID = UUID.fromString("2ed0bd49-a057-460d-8f4b-01693a97232a");

    @Test
    void samePlayerAndStoreReuseTargetQueryWithinTtl() {
        CommandTargetQueryCache cache = new CommandTargetQueryCache(200L);
        Object store = new Object();
        AtomicInteger queryCount = new AtomicInteger();

        UUID first = cache.resolve(store, PLAYER_UUID, 1_000L, () -> {
            queryCount.incrementAndGet();
            return FIRST_TARGET_UUID;
        });
        UUID cached = cache.resolve(store, PLAYER_UUID, 1_199L, () -> {
            queryCount.incrementAndGet();
            return SECOND_TARGET_UUID;
        });

        Assertions.assertEquals(FIRST_TARGET_UUID, first);
        Assertions.assertEquals(FIRST_TARGET_UUID, cached);
        Assertions.assertEquals(1, queryCount.get(), "Two HUD consumers must share one reticle query.");
    }

    @Test
    void targetQueryRunsAgainWhenCachedResultExpires() {
        CommandTargetQueryCache cache = new CommandTargetQueryCache(200L);
        Object store = new Object();
        AtomicInteger queryCount = new AtomicInteger();

        cache.resolve(store, PLAYER_UUID, 1_000L, () -> {
            queryCount.incrementAndGet();
            return FIRST_TARGET_UUID;
        });
        UUID refreshed = cache.resolve(store, PLAYER_UUID, 1_200L, () -> {
            queryCount.incrementAndGet();
            return SECOND_TARGET_UUID;
        });

        Assertions.assertEquals(SECOND_TARGET_UUID, refreshed);
        Assertions.assertEquals(2, queryCount.get());
    }

    @Test
    void missingTargetIsAlsoCachedWithinTtl() {
        CommandTargetQueryCache cache = new CommandTargetQueryCache(200L);
        Object store = new Object();
        AtomicInteger queryCount = new AtomicInteger();

        UUID first = cache.resolve(store, PLAYER_UUID, 1_000L, () -> {
            queryCount.incrementAndGet();
            return null;
        });
        UUID cached = cache.resolve(store, PLAYER_UUID, 1_100L, () -> {
            queryCount.incrementAndGet();
            return FIRST_TARGET_UUID;
        });

        Assertions.assertNull(first);
        Assertions.assertNull(cached);
        Assertions.assertEquals(1, queryCount.get());
    }
}
