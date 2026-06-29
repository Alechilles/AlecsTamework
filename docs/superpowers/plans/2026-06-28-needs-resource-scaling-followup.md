# Needs Resource Scaling Follow-Up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce remaining companion needs/resource overhead for large herds by sharing nearby resource search results, reusing recent path preflights, applying the same search policy to food, and ticking satisfied companions less often.

**Architecture:** Keep Hytale world probing in `CompanionNeedsEnvironmentService`, but move cache-key, preflight-reuse, and cadence policy into small package-private helpers under `npc.progression`. The changes are intentionally conservative: shared cache entries are short-lived, only reusable when still valid for the current NPC position, and target-specific rejectors can bypass shared hits that are no longer legal for an individual NPC.

**Tech Stack:** Java, Hytale ECS/world APIs, JUnit 5, Maven wrapper `.\mvnw.cmd`.

---

## File Structure

- Create `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceAreaSearchCache.java`
  - Owns a process-local, bounded, short-TTL cache for area-level water/food search results.
  - Keys exclude NPC UUID so cows in the same world/area/resource/radius can share equivalent scan results.
  - Stores only copied `Vector3d` targets and miss metadata.
- Create `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceAreaSearchCacheTest.java`
  - Verifies key normalization, cache expiry, target copying, bounded pruning, and source-absent miss reuse.
- Modify `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java`
  - Looks up area cache before full water/food ring scans.
  - Writes hits and source-absent misses into the area cache after scans.
  - Keeps per-NPC cache behavior for source-present misses and rejector-specific cases.
- Create `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourcePreflightPolicy.java`
  - Owns short-lived same-target preflight reuse thresholds and key conversion.
- Modify `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourcePathPreflightService.java`
  - Reuses recent READY path preflight results for the same NPC/resource/motion/target/approach distance while the current start remains close to the previously verified path.
- Modify `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourcePathPreflightServiceTest.java`
  - Adds tests for recent READY reuse and expiry.
- Create `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsSweepIntervalPolicy.java`
  - Owns urgency-aware passive needs interval selection from hunger/thirst ratios.
- Modify `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsService.java`
  - Uses urgency-aware intervals inside `tickNeedsIfDue`.
- Modify `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsSweepSchedulerTest.java`
  - Adds adaptive-interval tests without creating a world fixture.

### Task 1: Shared Area Cache For Water Search

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceAreaSearchCache.java`
- Create: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceAreaSearchCacheTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java`

- [ ] **Step 1: Write failing area-cache tests**

Create `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceAreaSearchCacheTest.java`:

```java
package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class NeedsResourceAreaSearchCacheTest {
    @Test
    void nearbyPositionsShareAreaKeyWithoutNpcUuid() {
        NeedsResourceAreaSearchCache.AreaKey first = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(10.25, 64.0, 10.25), 16.0, 2, 3.0, 0);
        NeedsResourceAreaSearchCache.AreaKey second = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(11.75, 64.0, 11.75), 16.0, 2, 3.0, 0);

        assertEquals(first, second);
    }

    @Test
    void cachedTargetIsCopiedOnWriteAndRead() {
        NeedsResourceAreaSearchCache cache = new NeedsResourceAreaSearchCache(16);
        NeedsResourceAreaSearchCache.AreaKey key = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);
        Vector3d target = new Vector3d(12.0, 64.0, 12.0);

        cache.put(key, NeedsResourceAreaSearchCache.AreaSearchSnapshot.hit(target, 2.0, 1_500L), 1_000L);
        target.set(99.0, 99.0, 99.0);
        NeedsResourceAreaSearchCache.AreaSearchSnapshot cached = cache.get(key, new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 1_100L);

        assertTrue(cached.hasTarget());
        assertNotSame(target, cached.target());
        assertEquals(12.0, cached.target().x);
    }

    @Test
    void expiredEntriesAreNotReturned() {
        NeedsResourceAreaSearchCache cache = new NeedsResourceAreaSearchCache(16);
        NeedsResourceAreaSearchCache.AreaKey key = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);

        cache.put(key, NeedsResourceAreaSearchCache.AreaSearchSnapshot.sourceAbsentMiss(15_000L), 1_000L);

        assertNull(cache.get(key, new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 16_001L));
    }

    @Test
    void targetOutsideCurrentRadiusIsRejected() {
        NeedsResourceAreaSearchCache cache = new NeedsResourceAreaSearchCache(16);
        NeedsResourceAreaSearchCache.AreaKey key = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);

        cache.put(key, NeedsResourceAreaSearchCache.AreaSearchSnapshot.hit(new Vector3d(20.0, 64.0, 10.0), 2.0, 1_500L), 1_000L);

        assertNull(cache.get(key, new Vector3d(10.0, 64.0, 10.0), 4.0, 2, 1_100L));
    }

    @Test
    void sourcePresentMissIsNotAreaReusable() {
        assertFalse(NeedsResourceAreaSearchCache.shouldShareResult(false, true));
    }

    @Test
    void hitAndSourceAbsentMissAreAreaReusable() {
        assertTrue(NeedsResourceAreaSearchCache.shouldShareResult(true, true));
        assertTrue(NeedsResourceAreaSearchCache.shouldShareResult(false, false));
    }
}
```

- [ ] **Step 2: Run tests and confirm they fail**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceAreaSearchCacheTest test
```

Expected: compile failure because `NeedsResourceAreaSearchCache` does not exist.

- [ ] **Step 3: Add the shared area-cache helper**

Create `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceAreaSearchCache.java`:

```java
package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Short-lived area-level cache for needs resource search results shared by nearby companions.
 */
final class NeedsResourceAreaSearchCache {
    static final int POSITION_CACHE_CELL_SIZE_BLOCKS = 4;

    private final int maxEntries;
    private final ConcurrentHashMap<AreaKey, CachedAreaSearch> entries = new ConcurrentHashMap<>();

    NeedsResourceAreaSearchCache(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
    }

    @Nullable
    AreaSearchSnapshot get(@Nullable AreaKey key,
                           @Nullable Vector3d currentPosition,
                           double radius,
                           int verticalScanRadius,
                           long nowMs) {
        if (key == null) {
            return null;
        }
        CachedAreaSearch cached = entries.get(key);
        if (cached == null) {
            return null;
        }
        if (nowMs >= cached.expiresAtMs()) {
            entries.remove(key, cached);
            return null;
        }
        if (!NeedsResourceSearchCachePolicy.isCachedTargetUsable(
                currentPosition,
                cached.target(),
                radius,
                verticalScanRadius
        )) {
            return null;
        }
        return cached.toSnapshot(nowMs);
    }

    void put(@Nullable AreaKey key, @Nonnull AreaSearchSnapshot snapshot, long nowMs) {
        if (key == null || !shouldShareResult(snapshot.hasTarget(), snapshot.foundConsumableSource())) {
            return;
        }
        pruneExpired(nowMs);
        if (entries.size() >= maxEntries) {
            return;
        }
        entries.put(key, CachedAreaSearch.from(snapshot, nowMs));
    }

    void clearForTests() {
        entries.clear();
    }

    static boolean shouldShareResult(boolean hasTarget, boolean foundConsumableSource) {
        return hasTarget || !foundConsumableSource;
    }

    private void pruneExpired(long nowMs) {
        if (entries.size() < maxEntries) {
            return;
        }
        entries.entrySet().removeIf(entry -> entry == null
                || entry.getValue() == null
                || nowMs >= entry.getValue().expiresAtMs());
    }

    record AreaKey(@Nonnull String worldName,
                   @Nonnull String resourceKind,
                   int cellX,
                   int cellY,
                   int cellZ,
                   int radiusKey,
                   int verticalScanRadius,
                   int consumeRadiusKey,
                   int itemIdsHash) {
        @Nullable
        static AreaKey from(@Nullable String worldName,
                            @Nonnull String resourceKind,
                            @Nullable Vector3d position,
                            double radius,
                            int verticalScanRadius,
                            double consumeRadius,
                            int itemIdsHash) {
            if (worldName == null || worldName.isBlank() || position == null
                    || !Double.isFinite(position.x)
                    || !Double.isFinite(position.y)
                    || !Double.isFinite(position.z)
                    || !Double.isFinite(radius)
                    || radius <= 0.0) {
                return null;
            }
            return new AreaKey(
                    worldName.trim().toLowerCase(Locale.ROOT),
                    resourceKind.trim().toLowerCase(Locale.ROOT),
                    Math.floorDiv((int) Math.floor(position.x), POSITION_CACHE_CELL_SIZE_BLOCKS),
                    Math.floorDiv((int) Math.floor(position.y), POSITION_CACHE_CELL_SIZE_BLOCKS),
                    Math.floorDiv((int) Math.floor(position.z), POSITION_CACHE_CELL_SIZE_BLOCKS),
                    Math.max(1, (int) Math.ceil(radius * 10.0)),
                    Math.max(0, verticalScanRadius),
                    Math.max(0, (int) Math.ceil(consumeRadius * 10.0)),
                    itemIdsHash
            );
        }
    }

    record AreaSearchSnapshot(@Nullable Vector3d target,
                              boolean foundConsumableSource,
                              boolean foundConsumableSourceInConsumeRange,
                              double approachRadius,
                              long ttlMs) {
        @Nonnull
        static AreaSearchSnapshot hit(@Nonnull Vector3d target, double approachRadius, long ttlMs) {
            return new AreaSearchSnapshot(new Vector3d(target), true, true, approachRadius, ttlMs);
        }

        @Nonnull
        static AreaSearchSnapshot sourceAbsentMiss(long ttlMs) {
            return new AreaSearchSnapshot(null, false, false, 2.0, ttlMs);
        }

        boolean hasTarget() {
            return target != null;
        }
    }

    private record CachedAreaSearch(@Nullable Vector3d target,
                                    boolean foundConsumableSource,
                                    boolean foundConsumableSourceInConsumeRange,
                                    double approachRadius,
                                    long expiresAtMs) {
        @Nonnull
        static CachedAreaSearch from(@Nonnull AreaSearchSnapshot snapshot, long nowMs) {
            return new CachedAreaSearch(
                    snapshot.target() != null ? new Vector3d(snapshot.target()) : null,
                    snapshot.foundConsumableSource(),
                    snapshot.foundConsumableSourceInConsumeRange(),
                    snapshot.approachRadius(),
                    nowMs + Math.max(1L, snapshot.ttlMs())
            );
        }

        @Nonnull
        AreaSearchSnapshot toSnapshot(long nowMs) {
            return new AreaSearchSnapshot(
                    target != null ? new Vector3d(target) : null,
                    foundConsumableSource,
                    foundConsumableSourceInConsumeRange,
                    approachRadius,
                    Math.max(1L, expiresAtMs - nowMs)
            );
        }
    }
}
```

- [ ] **Step 4: Run cache tests**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceAreaSearchCacheTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Wire area cache into water search**

In `CompanionNeedsEnvironmentService`, add a field near `SEARCH_CACHE`:

```java
private static final NeedsResourceAreaSearchCache AREA_SEARCH_CACHE =
        new NeedsResourceAreaSearchCache(SEARCH_CACHE_MAX_ENTRIES);
```

In `findNearestWaterDrinkingTarget`, after `NeedsSearchCacheKey cacheKey = buildSearchCacheKey(...)`, add an area key:

```java
NeedsResourceAreaSearchCache.AreaKey areaCacheKey = buildAreaSearchCacheKey(
        store,
        ResourceSearchKind.WATER,
        transform.getPosition(),
        radius,
        verticalScanRadius,
        consumeRadius,
        0
);
```

After the existing per-NPC cached-result check and before resolving `ChunkStore`, add:

```java
WaterTargetSearchResult areaCachedResult = getAreaCachedWaterSearchResult(
        areaCacheKey,
        transform.getPosition(),
        radius,
        verticalScanRadius,
        targetRejector,
        nowMs
);
if (areaCachedResult != null) {
    cacheWaterSearchResult(cacheKey, areaCachedResult, nowMs);
    return areaCachedResult;
}
```

After `cacheWaterSearchResult(cacheKey, bestResult, nowMs);`, add:

```java
cacheAreaWaterSearchResult(areaCacheKey, bestResult, nowMs);
```

Add helper methods near the existing search-cache helpers:

```java
@Nullable
private static NeedsResourceAreaSearchCache.AreaKey buildAreaSearchCacheKey(@Nullable Store<EntityStore> store,
                                                                            @Nonnull ResourceSearchKind resourceKind,
                                                                            @Nullable Vector3d position,
                                                                            double radius,
                                                                            int verticalScanRadius,
                                                                            double consumeRadius,
                                                                            int itemIdsHash) {
    World world = resolveWorld(store);
    String worldName = world != null ? world.getName() : null;
    return NeedsResourceAreaSearchCache.AreaKey.from(
            worldName,
            resourceKind.name(),
            position,
            radius,
            verticalScanRadius,
            consumeRadius,
            itemIdsHash
    );
}

@Nullable
private static WaterTargetSearchResult getAreaCachedWaterSearchResult(
        @Nullable NeedsResourceAreaSearchCache.AreaKey cacheKey,
        @Nullable Vector3d currentPosition,
        double radius,
        int verticalScanRadius,
        @Nullable TargetRejector targetRejector,
        long nowMs) {
    NeedsResourceAreaSearchCache.AreaSearchSnapshot snapshot =
            AREA_SEARCH_CACHE.get(cacheKey, currentPosition, radius, verticalScanRadius, nowMs);
    if (snapshot == null) {
        return null;
    }
    Vector3d target = snapshot.target();
    if (target != null && targetRejector != null && targetRejector.rejects(target)) {
        return null;
    }
    if (target != null) {
        return WaterTargetSearchResult.target(target, snapshot.approachRadius());
    }
    return WaterTargetSearchResult.miss(
            snapshot.foundConsumableSource(),
            snapshot.foundConsumableSourceInConsumeRange()
    );
}

private static void cacheAreaWaterSearchResult(@Nullable NeedsResourceAreaSearchCache.AreaKey cacheKey,
                                               @Nonnull WaterTargetSearchResult result,
                                               long nowMs) {
    long ttlMs = NeedsResourceSearchCachePolicy.baseTtlMs(
            result.target() != null,
            result.foundConsumableSource()
    );
    NeedsResourceAreaSearchCache.AreaSearchSnapshot snapshot = result.target() != null
            ? NeedsResourceAreaSearchCache.AreaSearchSnapshot.hit(result.target(), result.approachRadius(), ttlMs)
            : NeedsResourceAreaSearchCache.AreaSearchSnapshot.sourceAbsentMiss(ttlMs);
    AREA_SEARCH_CACHE.put(cacheKey, snapshot, nowMs);
}
```

Change `WaterTargetSearchResult.miss(boolean foundConsumableSource, boolean foundConsumableSourceInConsumeRange)` from `private` to package-private or public inside the record so `getAreaCachedWaterSearchResult` can call it from the enclosing class if needed by the compiler.

- [ ] **Step 6: Run focused tests and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceAreaSearchCacheTest,NeedsResourceSearchCachePolicyTest,CompanionNeedsEnvironmentServiceWaterTargetTest test
```

Expected: `BUILD SUCCESS`.

Commit:

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceAreaSearchCache.java src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceAreaSearchCacheTest.java src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java
git commit -m "Perf: share needs water search area cache"
```

### Task 2: Same-Target Path Preflight Reuse

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourcePreflightPolicy.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourcePathPreflightService.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourcePathPreflightServiceTest.java`

- [ ] **Step 1: Write failing recent-ready tests**

Add to `NeedsResourcePathPreflightServiceTest`:

```java
@Test
void recentReadyTargetAvoidsRecomputingWhenStartMovesNearby() {
    NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
    AtomicInteger factoryCalls = new AtomicInteger();
    NeedsResourcePathPreflightService.PreflightKey firstKey = keyFor(50, 2.0);
    NeedsResourcePathPreflightService.PreflightKey secondKey = NeedsResourcePathPreflightService.PreflightKey.from(
            new UUID(0L, 50L),
            "test-world",
            "FoodContainer",
            "Walk",
            new Vector3d(51.0, 64.0, 0.0),
            new Vector3d(54.0, 64.0, 0.0),
            2.0
    );

    PathPreflightResult first = service.preflight(
            firstKey,
            () -> {
                factoryCalls.incrementAndGet();
                return new FakeComputation(PathPreflightStatus.READY);
            },
            1_000L
    );
    PathPreflightResult second = service.preflight(
            secondKey,
            () -> {
                factoryCalls.incrementAndGet();
                return new FakeComputation(PathPreflightStatus.NO_PATH);
            },
            1_100L
    );

    assertTrue(first.ready());
    assertTrue(second.ready());
    assertEquals("path_preflight_recent_ready_target", second.reason());
    assertEquals(1, factoryCalls.get());
}

@Test
void recentReadyTargetExpires() {
    NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
    AtomicInteger factoryCalls = new AtomicInteger();
    NeedsResourcePathPreflightService.PreflightKey firstKey = keyFor(60, 2.0);
    NeedsResourcePathPreflightService.PreflightKey secondKey = NeedsResourcePathPreflightService.PreflightKey.from(
            new UUID(0L, 60L),
            "test-world",
            "FoodContainer",
            "Walk",
            new Vector3d(61.0, 64.0, 0.0),
            new Vector3d(64.0, 64.0, 0.0),
            2.0
    );

    service.preflight(
            firstKey,
            () -> {
                factoryCalls.incrementAndGet();
                return new FakeComputation(PathPreflightStatus.READY);
            },
            1_000L
    );
    PathPreflightResult second = service.preflight(
            secondKey,
            () -> {
                factoryCalls.incrementAndGet();
                return new FakeComputation(PathPreflightStatus.NO_PATH);
            },
            1_000L + NeedsResourcePreflightPolicy.RECENT_READY_TTL_MS + 1L
    );

    assertTrue(second.noPath());
    assertEquals(2, factoryCalls.get());
}
```

- [ ] **Step 2: Run tests and confirm failure**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourcePathPreflightServiceTest test
```

Expected: compile failure because `NeedsResourcePreflightPolicy` does not exist or assertion failure because recent-ready reuse is not implemented.

- [ ] **Step 3: Add the preflight policy helper**

Create `NeedsResourcePreflightPolicy.java`:

```java
package com.alechilles.alecstamework.npc.progression;

import javax.annotation.Nonnull;

/**
 * Conservative reuse policy for needs resource path preflights.
 */
final class NeedsResourcePreflightPolicy {
    static final long RECENT_READY_TTL_MS = 3_000L;
    private static final int MAX_START_BLOCK_DELTA = 2;

    private NeedsResourcePreflightPolicy() {
    }

    static boolean canReuseRecentReady(@Nonnull NeedsResourcePathPreflightService.PreflightKey previous,
                                       @Nonnull NeedsResourcePathPreflightService.PreflightKey current) {
        if (!previous.npcUuid().equals(current.npcUuid())
                || !previous.worldName().equals(current.worldName())
                || !previous.resourceType().equals(current.resourceType())
                || !previous.motionControllerType().equals(current.motionControllerType())
                || previous.targetX() != current.targetX()
                || previous.targetY() != current.targetY()
                || previous.targetZ() != current.targetZ()
                || previous.stopDistanceKey() != current.stopDistanceKey()) {
            return false;
        }
        return Math.abs(previous.startX() - current.startX()) <= MAX_START_BLOCK_DELTA
                && Math.abs(previous.startY() - current.startY()) <= 1
                && Math.abs(previous.startZ() - current.startZ()) <= MAX_START_BLOCK_DELTA;
    }
}
```

- [ ] **Step 4: Store and read recent READY preflights**

In `NeedsResourcePathPreflightService`, add a map field:

```java
private final ConcurrentHashMap<RecentReadyKey, RecentReadyPreflight> recentReadyTargets = new ConcurrentHashMap<>();
```

At the start of package-private `preflight(PreflightKey key, PathComputationFactory computationFactory, long nowMs)`, before checking `cache.get(key)`, add:

```java
PathPreflightResult recentReady = resolveRecentReady(key, nowMs);
if (recentReady != null) {
    return recentReady;
}
```

After `cacheTerminalResult(key, PathPreflightStatus.READY, "path_preflight_ready", nowMs);`, add:

```java
cacheRecentReady(key, nowMs);
```

In `clearForTests`, clear the new map:

```java
recentReadyTargets.clear();
```

Add helper records and methods near `CachedPreflight`:

```java
@Nullable
private PathPreflightResult resolveRecentReady(@Nonnull PreflightKey key, long nowMs) {
    RecentReadyKey recentKey = RecentReadyKey.from(key);
    RecentReadyPreflight recent = recentReadyTargets.get(recentKey);
    if (recent == null) {
        return null;
    }
    if (nowMs >= recent.expiresAtMs()) {
        recentReadyTargets.remove(recentKey, recent);
        return null;
    }
    if (!NeedsResourcePreflightPolicy.canReuseRecentReady(recent.key(), key)) {
        return null;
    }
    return PathPreflightResult.ready("path_preflight_recent_ready_target");
}

private void cacheRecentReady(@Nonnull PreflightKey key, long nowMs) {
    recentReadyTargets.put(
            RecentReadyKey.from(key),
            new RecentReadyPreflight(key, nowMs + NeedsResourcePreflightPolicy.RECENT_READY_TTL_MS)
    );
}

private record RecentReadyKey(@Nonnull UUID npcUuid,
                              @Nonnull String worldName,
                              @Nonnull String resourceType,
                              @Nonnull String motionControllerType,
                              int targetX,
                              int targetY,
                              int targetZ,
                              int stopDistanceKey) {
    @Nonnull
    static RecentReadyKey from(@Nonnull PreflightKey key) {
        return new RecentReadyKey(
                key.npcUuid(),
                key.worldName(),
                key.resourceType(),
                key.motionControllerType(),
                key.targetX(),
                key.targetY(),
                key.targetZ(),
                key.stopDistanceKey()
        );
    }
}

private record RecentReadyPreflight(@Nonnull PreflightKey key, long expiresAtMs) {
}
```

- [ ] **Step 5: Run focused tests and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourcePathPreflightServiceTest test
```

Expected: `BUILD SUCCESS`.

Commit:

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourcePreflightPolicy.java src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourcePathPreflightService.java src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourcePathPreflightServiceTest.java
git commit -m "Perf: reuse recent needs path preflights"
```

### Task 3: Food Search Parity

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceAreaSearchCacheTest.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicyTest.java`

- [ ] **Step 1: Add food-specific cache-key tests**

Add to `NeedsResourceAreaSearchCacheTest`:

```java
@Test
void foodItemHashSeparatesAreaKeys() {
    NeedsResourceAreaSearchCache.AreaKey beefKey = NeedsResourceAreaSearchCache.AreaKey.from(
            "world", "food_container", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 123);
    NeedsResourceAreaSearchCache.AreaKey wheatKey = NeedsResourceAreaSearchCache.AreaKey.from(
            "world", "food_container", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 456);

    assertFalse(beefKey.equals(wheatKey));
}

@Test
void foodAndWaterDoNotShareAreaKeys() {
    NeedsResourceAreaSearchCache.AreaKey foodKey = NeedsResourceAreaSearchCache.AreaKey.from(
            "world", "food_container", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);
    NeedsResourceAreaSearchCache.AreaKey waterKey = NeedsResourceAreaSearchCache.AreaKey.from(
            "world", "water", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);

    assertFalse(foodKey.equals(waterKey));
}
```

- [ ] **Step 2: Run cache tests**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceAreaSearchCacheTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Wire area cache into food search**

In `findNearestFoodContainerTarget`, after `NeedsSearchCacheKey cacheKey = buildSearchCacheKey(...)`, add:

```java
int itemIdsHash = allowedFoods.hashCode();
NeedsResourceAreaSearchCache.AreaKey areaCacheKey = buildAreaSearchCacheKey(
        store,
        ResourceSearchKind.FOOD_CONTAINER,
        transform.getPosition(),
        radius,
        verticalScanRadius,
        effectiveConsumeRadius,
        itemIdsHash
);
```

After the existing per-NPC cached-result check and before resolving `ChunkStore`, add:

```java
FoodTargetSearchResult areaCachedResult = getAreaCachedFoodSearchResult(
        areaCacheKey,
        transform.getPosition(),
        radius,
        verticalScanRadius,
        targetRejector,
        nowMs
);
if (areaCachedResult != null) {
    cacheFoodSearchResult(cacheKey, areaCachedResult, nowMs);
    return areaCachedResult;
}
```

After `cacheFoodSearchResult(cacheKey, bestResult, nowMs);`, add:

```java
cacheAreaFoodSearchResult(areaCacheKey, bestResult, nowMs);
```

Add food helper methods beside the water area-cache helpers:

```java
@Nullable
private static FoodTargetSearchResult getAreaCachedFoodSearchResult(
        @Nullable NeedsResourceAreaSearchCache.AreaKey cacheKey,
        @Nullable Vector3d currentPosition,
        double radius,
        int verticalScanRadius,
        @Nullable TargetRejector targetRejector,
        long nowMs) {
    NeedsResourceAreaSearchCache.AreaSearchSnapshot snapshot =
            AREA_SEARCH_CACHE.get(cacheKey, currentPosition, radius, verticalScanRadius, nowMs);
    if (snapshot == null) {
        return null;
    }
    Vector3d target = snapshot.target();
    if (target != null && targetRejector != null && targetRejector.rejects(target)) {
        return null;
    }
    if (target != null) {
        return FoodTargetSearchResult.target(target, snapshot.approachRadius());
    }
    return FoodTargetSearchResult.miss(
            snapshot.foundConsumableSource(),
            snapshot.foundConsumableSourceInConsumeRange()
    );
}

private static void cacheAreaFoodSearchResult(@Nullable NeedsResourceAreaSearchCache.AreaKey cacheKey,
                                              @Nonnull FoodTargetSearchResult result,
                                              long nowMs) {
    long ttlMs = NeedsResourceSearchCachePolicy.baseTtlMs(
            result.target() != null,
            result.foundConsumableSource()
    );
    NeedsResourceAreaSearchCache.AreaSearchSnapshot snapshot = result.target() != null
            ? NeedsResourceAreaSearchCache.AreaSearchSnapshot.hit(result.target(), result.approachRadius(), ttlMs)
            : NeedsResourceAreaSearchCache.AreaSearchSnapshot.sourceAbsentMiss(ttlMs);
    AREA_SEARCH_CACHE.put(cacheKey, snapshot, nowMs);
}
```

Change `FoodTargetSearchResult.miss(boolean foundConsumableSource, boolean foundConsumableSourceInConsumeRange)` from `private` to package-private or public inside the record so `getAreaCachedFoodSearchResult` can call it from the enclosing class if needed by the compiler.

- [ ] **Step 4: Run focused tests and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceAreaSearchCacheTest,NeedsResourceSearchCachePolicyTest,CompanionNeedsEnvironmentServiceWaterTargetTest test
```

Expected: `BUILD SUCCESS`.

Commit:

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceAreaSearchCacheTest.java src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicyTest.java
git commit -m "Perf: share needs food search area cache"
```

### Task 4: Urgency-Aware Needs Cadence

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsSweepIntervalPolicy.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsService.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsSweepSchedulerTest.java`

- [ ] **Step 1: Write failing cadence-policy tests**

Add to `NeedsSweepSchedulerTest`:

```java
@Test
void lowNeedsKeepBaseInterval() {
    assertEquals(2_000L, NeedsSweepIntervalPolicy.intervalMsForRatios(0.10, 0.90, 2_000L));
    assertEquals(2_000L, NeedsSweepIntervalPolicy.intervalMsForRatios(0.90, 0.10, 2_000L));
}

@Test
void satisfiedNeedsUseLongerInterval() {
    assertEquals(16_000L, NeedsSweepIntervalPolicy.intervalMsForRatios(0.90, 0.90, 2_000L));
}

@Test
void adaptiveIntervalIsCapped() {
    assertEquals(30_000L, NeedsSweepIntervalPolicy.intervalMsForRatios(1.0, 1.0, 10_000L));
}

@Test
void invalidBaseIntervalFallsBackToImmediateSweep() {
    assertEquals(0L, NeedsSweepIntervalPolicy.intervalMsForRatios(1.0, 1.0, 0L));
}
```

- [ ] **Step 2: Run tests and confirm failure**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsSweepSchedulerTest test
```

Expected: compile failure because `NeedsSweepIntervalPolicy` does not exist.

- [ ] **Step 3: Add the cadence policy helper**

Create `NeedsSweepIntervalPolicy.java`:

```java
package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Chooses passive needs update intervals from current hunger/thirst urgency.
 */
final class NeedsSweepIntervalPolicy {
    private static final long MAX_INTERVAL_MS = 30_000L;

    private NeedsSweepIntervalPolicy() {
    }

    static long intervalMs(@Nullable TameworkNeedsComponent component,
                           @Nonnull TwNeedsConfig config,
                           long baseIntervalMs) {
        if (component == null || baseIntervalMs <= 0L) {
            return 0L;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hungerRatio = ratio(component.getHunger(), values.getHungerMin(), values.getHungerMax());
        double thirstRatio = ratio(component.getThirst(), values.getThirstMin(), values.getThirstMax());
        return intervalMsForRatios(hungerRatio, thirstRatio, baseIntervalMs);
    }

    static long intervalMsForRatios(double hungerRatio, double thirstRatio, long baseIntervalMs) {
        if (baseIntervalMs <= 0L) {
            return 0L;
        }
        double urgency = Math.min(sanitizeRatio(hungerRatio), sanitizeRatio(thirstRatio));
        long multiplier;
        if (urgency <= 0.15) {
            multiplier = 1L;
        } else if (urgency <= 0.35) {
            multiplier = 2L;
        } else if (urgency <= 0.70) {
            multiplier = 4L;
        } else {
            multiplier = 8L;
        }
        return Math.min(MAX_INTERVAL_MS, baseIntervalMs * multiplier);
    }

    private static double ratio(double value, double min, double max) {
        if (!Double.isFinite(value) || !Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return 0.0;
        }
        return sanitizeRatio((value - min) / (max - min));
    }

    private static double sanitizeRatio(double ratio) {
        if (!Double.isFinite(ratio)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, ratio));
    }
}
```

- [ ] **Step 4: Apply adaptive interval in `CompanionNeedsService`**

In `tickNeedsIfDue`, replace:

```java
if (!NeedsSweepScheduler.shouldRunSweep(npcId, component.getLastPassiveSweepMs(), nowMs, intervalMs)) {
    return false;
}
```

with:

```java
long effectiveIntervalMs = NeedsSweepIntervalPolicy.intervalMs(component, config, intervalMs);
if (!NeedsSweepScheduler.shouldRunSweep(npcId, component.getLastPassiveSweepMs(), nowMs, effectiveIntervalMs)) {
    return false;
}
```

Keep `CompanionNeedsSystem` unchanged so the outer chunk scan cadence remains `SYSTEM_SWEEP_INTERVAL_MS`, and keep `requiresFrequentNaturalRegenSuppressionTick` unchanged so natural regen suppression still runs frequently when needed.

- [ ] **Step 5: Run focused tests and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsSweepSchedulerTest,CompanionNeedsServiceDamageTest test
```

Expected: `BUILD SUCCESS`.

Commit:

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/progression/NeedsSweepIntervalPolicy.java src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsService.java src/test/java/com/alechilles/alecstamework/npc/progression/NeedsSweepSchedulerTest.java
git commit -m "Perf: adapt needs sweep cadence to urgency"
```

### Task 5: Final Verification

**Files:**
- Verify only; no source edits expected.

- [ ] **Step 1: Run focused needs suite**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceAreaSearchCacheTest,NeedsResourceSearchCachePolicyTest,CompanionNeedsEnvironmentServiceWaterTargetTest,NeedsResourcePathPreflightServiceTest,NeedsSweepSchedulerTest,CompanionNeedsServiceDamageTest,CompanionNeedsConsumeServiceTest,SensorTameworkNeedsResourceTargetItemIdsTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Run ECS/player thread-affinity grep**

Run:

```powershell
rg "PlayerRef\.getComponent\(Player|getComponent\(Player\.getComponentType\(\)\)|Universe\.get\(\).*getPlayers" -n src/main/java
```

Expected: no matches in runtime tick/system paths introduced by this plan.

- [ ] **Step 3: Run guard tests**

Run:

```powershell
.\mvnw.cmd -Dtest=EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest,DamageExecutionWriteSafetyGuardTest,NeedsDamageDispatchGuardTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Run full test suite**

Run:

```powershell
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Build a comparison artifact**

Run:

```powershell
.\mvnw.cmd -DskipTests package
```

Expected: jar/package build succeeds. Use this build for the next Spark comparison with the same herd size, same trough layout, same warm-up, and a same-length profiling window.

## Self-Review

- Spec coverage: Task 1 implements shared area search cache; Task 2 implements path preflight reuse; Task 3 applies the cache to food search; Task 4 implements adaptive needs cadence.
- Placeholder scan: no task depends on unstated behavior; each code edit has a concrete target file and snippet.
- Type consistency: helper names are stable across tasks: `NeedsResourceAreaSearchCache`, `NeedsResourcePreflightPolicy`, and `NeedsSweepIntervalPolicy`.
- Risk control: all shared-cache entries are short-lived and validated against the current position/radius/vertical range before reuse. Source-present misses are not shared because they can depend on stand-target geometry and individual rejectors.
