# Needs Resource Search Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce companion needs resource-search cost without changing the visible hunger/thirst gameplay contract.

**Architecture:** Keep resource semantics in `CompanionNeedsEnvironmentService`, but extract cache-key and sweep-scheduling policy into small package-private helpers so the existing large service does not absorb more policy logic. Optimize in four layers: coarser cache keys, stronger absent-source TTLs, target-first consume probes, and staggered needs-system sweeps.

**Tech Stack:** Java, JUnit 5, Maven wrapper (`.\mvnw.cmd`), Hytale ECS `CommandBuffer` tick paths.

---

## File Structure

- Create `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicy.java`
  - Owns quantized cache-origin coordinates, cached-target validity checks, and TTL constants.
- Create `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicyTest.java`
  - Tests cache-coordinate stability, target validity, and absent-source TTL policy without world fixtures.
- Create `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsSweepScheduler.java`
  - Owns stable per-NPC sweep staggering for `CompanionNeedsSystem`.
- Create `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsSweepSchedulerTest.java`
  - Tests deterministic offsets and due/not-due behavior.
- Modify `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java`
  - Delegates cache-key coordinate calculation and TTL selection.
  - Adds exact target-first consume probes before local fallback scans.
- Modify `src/test/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentServiceWaterTargetTest.java`
  - Keeps existing metadata/cache tests and adds cache-policy integration coverage.
- Modify `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsConsumeService.java`
  - Uses target-first water/food helpers exposed by `CompanionNeedsEnvironmentService`.
- Modify `src/test/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsConsumeServiceTest.java`
  - Adds small pure tests for consume-origin acceptance/fallback helpers where possible.
- Modify `src/main/java/com/alechilles/alecstamework/npc/systems/CompanionNeedsSystem.java`
  - Delegates staggered `tickNeeds` scheduling to `CompanionNeedsService` while preserving high-frequency regen suppression.

---

### Task 1: Coarsen Needs Search Cache Keys

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicy.java`
- Create: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicyTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentServiceWaterTargetTest.java`

- [ ] **Step 1: Write cache-policy tests**

Add `NeedsResourceSearchCachePolicyTest`:

```java
package com.alechilles.alecstamework.npc.progression;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeedsResourceSearchCachePolicyTest {
    @Test
    void nearbyBlocksShareSearchCacheCell() {
        assertEquals(
                NeedsResourceSearchCachePolicy.quantizedBlockCoordinate(12),
                NeedsResourceSearchCachePolicy.quantizedBlockCoordinate(13)
        );
    }

    @Test
    void distantBlocksUseDifferentSearchCacheCells() {
        assertFalse(
                NeedsResourceSearchCachePolicy.quantizedBlockCoordinate(12)
                        == NeedsResourceSearchCachePolicy.quantizedBlockCoordinate(16)
        );
    }

    @Test
    void cachedTargetInsideRadiusIsUsable() {
        assertTrue(NeedsResourceSearchCachePolicy.isCachedTargetUsable(
                new Vector3d(10.5, 64.0, 10.5),
                new Vector3d(12.5, 64.0, 10.5),
                3.0,
                1
        ));
    }

    @Test
    void cachedTargetOutsideRadiusIsRejected() {
        assertFalse(NeedsResourceSearchCachePolicy.isCachedTargetUsable(
                new Vector3d(10.5, 64.0, 10.5),
                new Vector3d(15.5, 64.0, 10.5),
                3.0,
                1
        ));
    }

    @Test
    void cachedTargetOutsideVerticalScanIsRejected() {
        assertFalse(NeedsResourceSearchCachePolicy.isCachedTargetUsable(
                new Vector3d(10.5, 64.0, 10.5),
                new Vector3d(10.5, 67.0, 10.5),
                4.0,
                1
        ));
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceSearchCachePolicyTest test
```

Expected: compile failure because `NeedsResourceSearchCachePolicy` does not exist.

- [ ] **Step 3: Implement the cache policy helper**

Create `NeedsResourceSearchCachePolicy.java`:

```java
package com.alechilles.alecstamework.npc.progression;

import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * Policy helpers for companion needs resource-search caching.
 */
final class NeedsResourceSearchCachePolicy {
    static final int POSITION_CACHE_CELL_SIZE_BLOCKS = 2;
    static final long HIT_TTL_MS = 1_500L;
    static final long SOURCE_PRESENT_MISS_TTL_MS = 3_000L;
    static final long SOURCE_ABSENT_MISS_TTL_MS = 15_000L;

    private static final double EPSILON = 0.000001;

    private NeedsResourceSearchCachePolicy() {
    }

    static int quantizedBlockCoordinate(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, POSITION_CACHE_CELL_SIZE_BLOCKS);
    }

    static long baseTtlMs(boolean hasTarget, boolean foundConsumableSource) {
        if (hasTarget) {
            return HIT_TTL_MS;
        }
        return foundConsumableSource ? SOURCE_PRESENT_MISS_TTL_MS : SOURCE_ABSENT_MISS_TTL_MS;
    }

    static boolean isCachedTargetUsable(@Nullable Vector3d currentPosition,
                                        @Nullable Vector3d cachedTarget,
                                        double radius,
                                        int verticalScanRadius) {
        if (cachedTarget == null) {
            return true;
        }
        if (currentPosition == null
                || !Double.isFinite(currentPosition.x)
                || !Double.isFinite(currentPosition.y)
                || !Double.isFinite(currentPosition.z)
                || !Double.isFinite(cachedTarget.x)
                || !Double.isFinite(cachedTarget.y)
                || !Double.isFinite(cachedTarget.z)
                || !Double.isFinite(radius)
                || radius <= 0.0) {
            return false;
        }
        double dx = cachedTarget.x - currentPosition.x;
        double dz = cachedTarget.z - currentPosition.z;
        if ((dx * dx) + (dz * dz) > (radius * radius) + EPSILON) {
            return false;
        }
        return Math.abs(Math.floor(cachedTarget.y) - Math.floor(currentPosition.y)) <= Math.max(0, verticalScanRadius);
    }
}
```

- [ ] **Step 4: Route cache keys through the helper**

In `CompanionNeedsEnvironmentService.buildSearchCacheKey`, replace exact block fields with quantized coordinates:

```java
int blockX = NeedsResourceSearchCachePolicy.quantizedBlockCoordinate((int) Math.floor(position.x));
int blockY = NeedsResourceSearchCachePolicy.quantizedBlockCoordinate((int) Math.floor(position.y));
int blockZ = NeedsResourceSearchCachePolicy.quantizedBlockCoordinate((int) Math.floor(position.z));
```

In `searchCacheTtlMs`, replace the local base TTL decision with:

```java
long baseTtlMs = NeedsResourceSearchCachePolicy.baseTtlMs(hasTarget, foundConsumableSource);
```

Then update cached result retrieval so positive cached targets are rechecked against the current position before returning. Change `getCachedWaterSearchResult` and `getCachedFoodSearchResult` signatures to accept `Vector3d currentPosition`, `double radius`, and `int verticalScanRadius`. Before constructing the result, add:

```java
if (!NeedsResourceSearchCachePolicy.isCachedTargetUsable(
        currentPosition,
        cached.target(),
        radius,
        verticalScanRadius
)) {
    SEARCH_CACHE.remove(cacheKey, cached);
    return null;
}
```

Update both call sites in `findNearestWaterDrinkingTarget` and `findNearestFoodContainerTarget` to pass `transform.getPosition()`, `radius`, and `verticalScanRadius`.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceSearchCachePolicyTest,CompanionNeedsEnvironmentServiceWaterTargetTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicy.java src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicyTest.java src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java src/test/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentServiceWaterTargetTest.java
git commit -m "Perf: coarsen needs resource search cache keys"
```

---

### Task 2: Strengthen Source-Absent Negative Caching

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicy.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicyTest.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentServiceWaterTargetTest.java`

- [ ] **Step 1: Write TTL tests**

Add these tests to `NeedsResourceSearchCachePolicyTest`:

```java
@Test
void sourceAbsentMissTtlIsLongEnoughToThrottleFullScans() {
    assertTrue(NeedsResourceSearchCachePolicy.baseTtlMs(false, false) >= 15_000L);
}

@Test
void sourcePresentMissStaysShorterThanSourceAbsentMiss() {
    assertTrue(NeedsResourceSearchCachePolicy.baseTtlMs(false, true)
            < NeedsResourceSearchCachePolicy.baseTtlMs(false, false));
}

@Test
void hitTtlStaysShortForMovingTargets() {
    assertTrue(NeedsResourceSearchCachePolicy.baseTtlMs(true, true)
            < NeedsResourceSearchCachePolicy.baseTtlMs(false, true));
}
```

- [ ] **Step 2: Run tests**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceSearchCachePolicyTest,CompanionNeedsEnvironmentServiceWaterTargetTest test
```

Expected: PASS if Task 1 used `SOURCE_ABSENT_MISS_TTL_MS = 15_000L`; otherwise fail until the constant is updated.

- [ ] **Step 3: Remove stale duplicated TTL constants**

After `CompanionNeedsEnvironmentService` delegates to `NeedsResourceSearchCachePolicy`, remove or stop using these local constants:

```java
private static final long SEARCH_CACHE_HIT_TTL_MS = 1_500L;
private static final long SEARCH_CACHE_MISS_TTL_MS = 3_000L;
private static final long SEARCH_CACHE_SOURCE_ABSENT_MISS_TTL_MS = 8_000L;
```

Keep `SEARCH_CACHE_MAX_ENTRIES` in `CompanionNeedsEnvironmentService`, because that class still owns the actual `ConcurrentHashMap`.

- [ ] **Step 4: Run focused tests again**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceSearchCachePolicyTest,CompanionNeedsEnvironmentServiceWaterTargetTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicy.java src/test/java/com/alechilles/alecstamework/npc/progression/NeedsResourceSearchCachePolicyTest.java src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java src/test/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentServiceWaterTargetTest.java
git commit -m "Perf: extend needs resource absent-cache backoff"
```

---

### Task 3: Try the Resolved Resource Target Before Sweeping Nearby Blocks

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsConsumeService.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsConsumeServiceTest.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentServiceWaterTargetTest.java`

- [ ] **Step 1: Add pure origin validation tests**

Add to `CompanionNeedsConsumeServiceTest`:

```java
@Test
void consumeOriginWithFiniteCoordinatesCanUseTargetFirstProbe() {
    assertTrue(CompanionNeedsConsumeService.canUseTargetFirstConsumeProbeForTests(
            new org.joml.Vector3d(1.5, 64.0, 2.5)
    ));
}

@Test
void consumeOriginWithNaNCoordinateSkipsTargetFirstProbe() {
    assertFalse(CompanionNeedsConsumeService.canUseTargetFirstConsumeProbeForTests(
            new org.joml.Vector3d(Double.NaN, 64.0, 2.5)
    ));
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=CompanionNeedsConsumeServiceTest test
```

Expected: compile failure because the test hook does not exist.

- [ ] **Step 3: Add target-first hooks in consume service**

In `CompanionNeedsConsumeService`, add:

```java
static boolean canUseTargetFirstConsumeProbeForTests(@Nullable Vector3d consumeOrigin) {
    return isFiniteConsumeOrigin(consumeOrigin);
}

private static boolean isFiniteConsumeOrigin(@Nullable Vector3d consumeOrigin) {
    return consumeOrigin != null
            && Double.isFinite(consumeOrigin.x)
            && Double.isFinite(consumeOrigin.y)
            && Double.isFinite(consumeOrigin.z);
}
```

- [ ] **Step 4: Add exact target-first environment methods**

In `CompanionNeedsEnvironmentService`, add package-private methods:

```java
boolean consumeWaterTroughChargeAt(@Nullable Store<EntityStore> store, @Nullable Vector3d consumeOrigin) {
    // Resolve world/chunk, floor consumeOrigin to block coordinates, verify isConsumableWaterTroughAt,
    // then call FeedTroughWaterStateService.consumeSingleCharge.
}

boolean isConsumableWaterAt(@Nullable Store<EntityStore> store, @Nullable Vector3d consumeOrigin) {
    // Resolve world/chunk, floor consumeOrigin to block coordinates, then call isConsumableWaterSourceAt.
}

ContainerConsumeResult consumeContainerFoodAtDetailed(@Nullable Ref<EntityStore> npcRef,
                                                      @Nullable Store<EntityStore> store,
                                                      @Nonnull TwNeedsConfig config,
                                                      @Nullable String roleId,
                                                      @Nullable String[] preferredFoodItemIds,
                                                      @Nullable Vector3d consumeOrigin) {
    // Resolve one exact container block, build the same allowed-food set as consumeNearbyContainerFoodDetailed,
    // consume from that container only, and return ContainerConsumeResult.SUCCESS or the same miss statuses.
}
```

Do not remove the existing scan methods. The exact methods are fast-path probes only.

- [ ] **Step 5: Use exact probes before fallback scans**

In `CompanionNeedsConsumeService.applyResourceConsumeInternal`, before `consumeNearbyContainerFoodDetailed`, do:

```java
CompanionNeedsEnvironmentService.ContainerConsumeResult containerResult =
        isFiniteConsumeOrigin(consumeOriginOverride)
                ? ENVIRONMENT_SERVICE.consumeContainerFoodAtDetailed(
                        npcRef,
                        store,
                        config,
                        roleId,
                        effectiveFoodIds,
                        consumeOriginOverride
                )
                : CompanionNeedsEnvironmentService.ContainerConsumeResult.notAttemptedForTargetProbe();
if (containerResult.getConsumedItems() <= 0) {
    containerResult = ENVIRONMENT_SERVICE.consumeNearbyContainerFoodDetailed(
            npcRef,
            store,
            config,
            roleId,
            effectiveFoodIds,
            consumeRadius,
            consumeOriginOverride
    );
}
```

If adding `notAttemptedForTargetProbe()` is too much churn, use a local nullable result instead:

```java
CompanionNeedsEnvironmentService.ContainerConsumeResult containerResult = null;
if (isFiniteConsumeOrigin(consumeOriginOverride)) {
    containerResult = ENVIRONMENT_SERVICE.consumeContainerFoodAtDetailed(
            npcRef,
            store,
            config,
            roleId,
            effectiveFoodIds,
            consumeOriginOverride
    );
}
if (containerResult == null || containerResult.getConsumedItems() <= 0) {
    containerResult = ENVIRONMENT_SERVICE.consumeNearbyContainerFoodDetailed(
            npcRef,
            store,
            config,
            roleId,
            effectiveFoodIds,
            consumeRadius,
            consumeOriginOverride
    );
}
```

For water, prefer the exact trough/open-water check before the local sweep:

```java
boolean consumedTroughCharge = isFiniteConsumeOrigin(consumeOriginOverride)
        && ENVIRONMENT_SERVICE.consumeWaterTroughChargeAt(store, consumeOriginOverride);
if (!consumedTroughCharge) {
    consumedTroughCharge = ENVIRONMENT_SERVICE.consumeNearbyWaterTroughCharge(
            npcRef,
            store,
            config,
            consumeOriginOverride
    );
}
boolean nearWater = consumedTroughCharge
        || (isFiniteConsumeOrigin(consumeOriginOverride)
                && ENVIRONMENT_SERVICE.isConsumableWaterAt(store, consumeOriginOverride))
        || ENVIRONMENT_SERVICE.isNearWater(npcRef, store, config, consumeOriginOverride);
```

- [ ] **Step 6: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CompanionNeedsConsumeServiceTest,CompanionNeedsEnvironmentServiceWaterTargetTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentService.java src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsConsumeService.java src/test/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsConsumeServiceTest.java src/test/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsEnvironmentServiceWaterTargetTest.java
git commit -m "Perf: consume needs resources from resolved target first"
```

---

### Task 4: Stagger Needs-System Sweeps

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsSweepScheduler.java`
- Create: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsSweepSchedulerTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/CompanionNeedsSystem.java`

- [ ] **Step 1: Write scheduler tests**

Create `NeedsSweepSchedulerTest`:

```java
package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeedsSweepSchedulerTest {
    @Test
    void stableOffsetIsDeterministicForNpc() {
        UUID npcId = new UUID(123L, 456L);
        assertEquals(
                NeedsSweepScheduler.stableOffsetMs(npcId, 2_000L),
                NeedsSweepScheduler.stableOffsetMs(npcId, 2_000L)
        );
    }

    @Test
    void stableOffsetStaysInsideInterval() {
        long offset = NeedsSweepScheduler.stableOffsetMs(new UUID(123L, 456L), 2_000L);
        assertTrue(offset >= 0L);
        assertTrue(offset < 2_000L);
    }

    @Test
    void dueWhenLastSweepPlusIntervalHasPassed() {
        assertTrue(NeedsSweepScheduler.shouldRunSweep(
                new UUID(1L, 2L),
                10_000L,
                12_000L,
                2_000L
        ));
    }

    @Test
    void notDueBeforeIntervalHasPassed() {
        assertFalse(NeedsSweepScheduler.shouldRunSweep(
                new UUID(1L, 2L),
                10_000L,
                11_000L,
                2_000L
        ));
    }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsSweepSchedulerTest test
```

Expected: compile failure because `NeedsSweepScheduler` does not exist.

- [ ] **Step 3: Implement the scheduler helper**

Create `NeedsSweepScheduler.java`:

```java
package com.alechilles.alecstamework.npc.progression;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Stable scheduling helpers for spreading companion needs work across ticks.
 */
final class NeedsSweepScheduler {
    private NeedsSweepScheduler() {
    }

    static long stableOffsetMs(@Nullable UUID npcId, long intervalMs) {
        if (npcId == null || intervalMs <= 1L) {
            return 0L;
        }
        long mixed = npcId.getMostSignificantBits() ^ Long.rotateLeft(npcId.getLeastSignificantBits(), 21);
        return Math.floorMod(mixed, intervalMs);
    }

    static boolean shouldRunSweep(@Nullable UUID npcId, long lastSweepMs, long nowMs, long intervalMs) {
        if (intervalMs <= 0L) {
            return true;
        }
        if (lastSweepMs <= 0L || lastSweepMs > nowMs) {
            return nowMs >= stableOffsetMs(npcId, intervalMs);
        }
        return nowMs - lastSweepMs >= intervalMs;
    }
}
```

- [ ] **Step 4: Add a scheduled tick entry point in `CompanionNeedsService`**

Add a service-owned entry point so due checks use the same `resolveNowMs(config, store)` clock basis as needs decay and so `LastPassiveSweepMs` writes stay inside the existing `putComponent`/`CommandBuffer` path.

Add:

```java
public static boolean tickNeedsIfDue(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store,
                                     @Nullable CommandBuffer<EntityStore> commandBuffer,
                                     @Nullable String roleId,
                                     long intervalMs) {
    if (npcRef == null || store == null || !npcRef.isValid()) {
        return false;
    }
    ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
    if (needsType == null) {
        return false;
    }
    TameworkNeedsComponent component = store.getComponent(npcRef, needsType);
    TwNeedsConfig config = resolveNeedsConfig(npcRef, store, roleId, component);
    if (!CompanionNeedsRuntimePolicy.isNeedsEnabled(config)) {
        return removeNeedsRuntimeState(npcRef, store, commandBuffer, needsType, component);
    }
    component = ensureNeedsComponent(npcRef, store, commandBuffer, roleId);
    if (component == null) {
        return false;
    }
    long nowMs = resolveNowMs(config, store);
    UUID npcId = resolveNpcUuid(npcRef, store);
    if (!NeedsSweepScheduler.shouldRunSweep(npcId, component.getLastPassiveSweepMs(), nowMs, intervalMs)) {
        return false;
    }
    return runNeedsUpdate(
            npcRef,
            store,
            roleId,
            0.0,
            0.0,
            false,
            true,
            commandBuffer,
            null,
            true
    );
}
```

Then add a private overload of `runNeedsUpdate` with a final `boolean markPassiveSweep` parameter. Existing public overloads pass `false`; `tickNeedsIfDue` passes `true`.

Inside the main `runNeedsUpdate`, after `LastUpdateMs` handling, add:

```java
if (markPassiveSweep && component.getLastPassiveSweepMs() != nowMs) {
    component.setLastPassiveSweepMs(nowMs);
    componentChanged = true;
}
```

- [ ] **Step 5: Apply scheduler in `CompanionNeedsSystem`**

In `CompanionNeedsSystem`, keep the outer `SYSTEM_SWEEP_INTERVAL_MS` gate as a cheap scan cadence, but replace the per-NPC unconditional needs tick.

Replace:

```java
if (runNeedsSweep) {
    CompanionNeedsService.tickNeeds(ref, store, commandBuffer, roleId);
    if (linkedOwnerId != null
            && starvingLinkedByOwner != null
            && CompanionNeedsService.isNeedsDamageActive(ref, store, roleId)) {
        starvingLinkedByOwner.merge(linkedOwnerId, 1, Integer::sum);
    }
}
```

With:

```java
if (runNeedsSweep) {
    boolean ticked = CompanionNeedsService.tickNeedsIfDue(
            ref,
            store,
            commandBuffer,
            roleId,
            SYSTEM_SWEEP_INTERVAL_MS
    );
    if (ticked
            && linkedOwnerId != null
            && starvingLinkedByOwner != null
            && CompanionNeedsService.isNeedsDamageActive(ref, store, roleId)) {
        starvingLinkedByOwner.merge(linkedOwnerId, 1, Integer::sum);
    }
} else if (CompanionNeedsService.requiresFrequentNaturalRegenSuppressionTick(ref, store, roleId)) {
    CompanionNeedsService.tickNaturalRegenSuppressionOnly(ref, store, commandBuffer, roleId);
}
```

- [ ] **Step 6: Validate decay cadence does not change**

Add a small unit test in an existing needs service test if a pure hook is available. The assertion should be: staggering only changes when `tickNeeds` is invoked, not how `effectiveElapsedMs` is computed once it runs. If no pure test hook exists, do not add a fragile world fixture; rely on `NeedsSweepSchedulerTest` plus existing `CompanionNeedsServiceDamageTest`.

- [ ] **Step 7: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=NeedsSweepSchedulerTest,CompanionNeedsServiceDamageTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/progression/NeedsSweepScheduler.java src/test/java/com/alechilles/alecstamework/npc/progression/NeedsSweepSchedulerTest.java src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsService.java src/main/java/com/alechilles/alecstamework/npc/systems/CompanionNeedsSystem.java
git commit -m "Perf: stagger companion needs sweeps"
```

---

## Final Verification

- [ ] Run focused needs tests:

```powershell
.\mvnw.cmd -Dtest=NeedsResourceSearchCachePolicyTest,CompanionNeedsEnvironmentServiceWaterTargetTest,CompanionNeedsConsumeServiceTest,NeedsSweepSchedulerTest,CompanionNeedsServiceDamageTest test
```

- [ ] Run ECS/player-access guard grep:

```powershell
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: no new unsafe matches in tick/runtime paths.

- [ ] Run runtime safety guard tests:

```powershell
.\mvnw.cmd -Dtest=EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

- [ ] Run full test suite:

```powershell
.\mvnw.cmd test
```

- [ ] Inspect staged diff before final commit or PR:

```powershell
git diff --stat
git diff -- src/main/java/com/alechilles/alecstamework/npc/progression src/main/java/com/alechilles/alecstamework/npc/systems src/test/java/com/alechilles/alecstamework/npc/progression
```

Expected: no unrelated HUD, language, agent-doc, or release files included.

## Notes

- Do not lower default search radii in this optimization pass. Histatu's config tuning is useful evidence, but upstream defaults should preserve gameplay reach unless profiling still shows unacceptable cost after cache and scheduling fixes.
- Do not add a new `TwNeedsConfig` field in this pass unless implementation proves a hard-coded policy is too risky. Config schema changes require codec, inheritance, docs, and wiki work.
- `CompanionNeedsEnvironmentService` is already large. Prefer extracting helper policy classes over adding broad private-helper sprawl.
