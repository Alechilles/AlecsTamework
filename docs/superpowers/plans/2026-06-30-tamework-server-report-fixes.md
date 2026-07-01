# Tamework Server Report Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the confirmed server-report issues around multi-world ECS singleton state, blocking HStats network calls, profile-state data loss, spawner duplication ordering, and interaction inventory economy semantics.

**Architecture:** Split the work into separately committable subsystems. Runtime ECS systems will move cross-tick mutable state out of shared singleton fields and into per-store state objects. Persistence and item-economy fixes will be TDD-first with focused regression tests before source changes.

**Tech Stack:** Java 25, Hytale ECS `TickingSystem`, JUnit 5, Maven Wrapper, SQLite persistence repositories, Hytale inventory `ItemStackTransaction`, Java `HttpURLConnection`/executors.

---

## Scope And Priority

Implement in this order:

1. Multi-world ECS singleton state and shared throttles.
2. HStats network timeouts and dedicated background executor.
3. `NpcProfileRepository` state JSON merge.
4. Spawner consume-before-spawn and capture stack guard.
5. Interaction inventory all-or-nothing costs and add remainders.
6. Lower-risk delayed UI/crash-telemetry cleanup.

Each numbered task below should end in a commit. Do not mix tasks in one commit unless a task explicitly says to.

## File Structure

- Create: `src/main/java/com/alechilles/alecstamework/util/StoreScopedState.java`
  - Small reusable helper for per-store state. It stores values by weak store key and synchronizes map access.
- Create: `src/test/java/com/alechilles/alecstamework/util/StoreScopedStateTest.java`
  - Pure Java tests for one-state-per-key, clear, and null rejection.
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/CompanionNeedsSystem.java`
  - Move `nextSweepAtMs` and `lastMalnourishmentWarningByOwner` into per-store state.
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/FlyingCompanionControlSystem.java`
  - Move `nextSweepAtMs`, `landingTargets`, and `debugSignatures` into per-store state.
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/*System.java` and `src/main/java/com/alechilles/alecstamework/items/*System.java`
  - Move remaining shared `nextSweepAtMs` fields in registered tick systems into per-store state.
- Create: `src/test/java/com/alechilles/alecstamework/architecture/SingletonTickingSystemStateGuardTest.java`
  - Static source guard so new singleton tick systems do not add plain shared throttles or `HashMap` state.
- Modify: `src/main/java/com/alechilles/alecstamework/metrics/HStats.java`
  - Add connect/read timeouts and offload all metrics calls to a dedicated daemon executor.
- Modify: `src/main/java/com/alechilles/alecstamework/metrics/TameworkHStatsIntegration.java`
  - Hold the `HStats` instance and close it during plugin shutdown if the plugin has an available stop hook.
- Create: `src/test/java/com/alechilles/alecstamework/metrics/HStatsSourceSafetyTest.java`
  - Static source guard for timeouts and no direct scheduling on `HytaleServer.SCHEDULED_EXECUTOR`.
- Modify: `src/main/java/com/alechilles/alecstamework/persistence/sqlite/NpcProfileRepository.java`
  - Merge non-null update state keys into existing `state_json` instead of replacing the blob.
- Modify: `src/test/java/com/alechilles/alecstamework/persistence/sqlite/NpcProfileRepositoryStateMergeTest.java`
  - Add regression tests for tame/name followed by coop update and coop followed by name/tame update.
- Modify: `src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java`
  - Validate single-item capture stacks and reorder spawn consumption so the item update succeeds before world mutation.
- Modify: `src/test/java/com/alechilles/alecstamework/items/SpawnerFeatureHandlerTest.java`
  - Add static/order tests for capture quantity guard and spawn consume-before-clear ordering.
- Modify: `src/main/java/com/alechilles/alecstamework/npc/actions/InteractionInventoryEffects.java`
  - Require full removal for costs and return/drop/deny add remainders.
- Create or modify: `src/test/java/com/alechilles/alecstamework/npc/actions/InteractionInventoryEffectsTest.java`
  - Add regression tests around partial removal and full inventory add behavior.
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java`
  - Guard delayed `world.execute(runnable)` calls with `world.isAlive()` and try/catch.
- Modify: `src/main/java/com/alechilles/alecstamework/metrics/CrashTelemetryService.java`
  - Defer legacy migration work off the setup thread, or bound it and log progress.
- Modify: `CHANGELOG.md`
  - Add player/admin-facing fixed entries under `Unreleased`.

---

### Task 1: Add Store-Scoped State Helper

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/util/StoreScopedState.java`
- Create: `src/test/java/com/alechilles/alecstamework/util/StoreScopedStateTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/alechilles/alecstamework/util/StoreScopedStateTest.java`:

```java
package com.alechilles.alecstamework.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StoreScopedStateTest {
    @Test
    void returnsSameStateForSameStoreKey() {
        StoreScopedState<State> states = new StoreScopedState<>(State::new);
        Object store = new Object();

        State first = states.get(store);
        State second = states.get(store);

        assertSame(first, second);
        assertEquals(1, states.sizeForTests());
    }

    @Test
    void returnsDifferentStateForDifferentStoreKeys() {
        StoreScopedState<State> states = new StoreScopedState<>(State::new);

        State first = states.get(new Object());
        State second = states.get(new Object());

        assertNotSame(first, second);
        assertEquals(2, states.sizeForTests());
    }

    @Test
    void removesStateForStoreKey() {
        StoreScopedState<State> states = new StoreScopedState<>(State::new);
        Object store = new Object();
        State first = states.get(store);

        states.remove(store);
        State second = states.get(store);

        assertNotSame(first, second);
        assertEquals(1, states.sizeForTests());
    }

    @Test
    void rejectsNullStoreKey() {
        StoreScopedState<State> states = new StoreScopedState<>(State::new);

        assertThrows(NullPointerException.class, () -> states.get(null));
    }

    private static final class State {
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\mvnw -Dtest=StoreScopedStateTest test
```

Expected: compile failure because `StoreScopedState` does not exist.

- [ ] **Step 3: Implement the helper**

Create `src/main/java/com/alechilles/alecstamework/util/StoreScopedState.java`:

```java
package com.alechilles.alecstamework.util;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Maintains mutable runtime state per live Hytale store instead of on a shared singleton system.
 *
 * <p>Hytale registers one system instance globally, so world stores can tick the same system object
 * concurrently. Use this helper for small cross-tick state objects that must be isolated per store.
 */
public final class StoreScopedState<T> {
    private final Supplier<T> factory;
    private final Map<Object, T> statesByStore = new WeakHashMap<>();

    public StoreScopedState(@Nonnull Supplier<T> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Nonnull
    public T get(@Nonnull Object store) {
        Objects.requireNonNull(store, "store");
        synchronized (statesByStore) {
            return statesByStore.computeIfAbsent(store, ignored -> factory.get());
        }
    }

    public void remove(@Nonnull Object store) {
        Objects.requireNonNull(store, "store");
        synchronized (statesByStore) {
            statesByStore.remove(store);
        }
    }

    int sizeForTests() {
        synchronized (statesByStore) {
            return statesByStore.size();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```powershell
.\mvnw -Dtest=StoreScopedStateTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/util/StoreScopedState.java src/test/java/com/alechilles/alecstamework/util/StoreScopedStateTest.java
git commit -m "Fix: add store-scoped runtime state helper" -m "Tests: .\mvnw -Dtest=StoreScopedStateTest test"
```

---

### Task 2: Fix CompanionNeedsSystem Singleton State

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/CompanionNeedsSystem.java`
- Create: `src/test/java/com/alechilles/alecstamework/architecture/SingletonTickingSystemStateGuardTest.java`

- [ ] **Step 1: Write the architecture guard**

Create `src/test/java/com/alechilles/alecstamework/architecture/SingletonTickingSystemStateGuardTest.java`:

```java
package com.alechilles.alecstamework.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class SingletonTickingSystemStateGuardTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void tickingSystemsDoNotDeclarePlainSharedHashMaps() throws IOException {
        List<Path> offenders;
        try (var stream = Files.walk(SOURCE_ROOT)) {
            offenders = stream
                    .filter(path -> path.toString().endsWith("System.java"))
                    .filter(SingletonTickingSystemStateGuardTest::containsTickingSystem)
                    .filter(SingletonTickingSystemStateGuardTest::containsPlainSharedHashMapField)
                    .toList();
        }

        assertTrue(offenders.isEmpty(), "Plain HashMap fields in registered ticking systems: " + offenders);
    }

    @Test
    void tickingSystemsDoNotDeclareSharedNextSweepField() throws IOException {
        List<Path> offenders;
        try (var stream = Files.walk(SOURCE_ROOT)) {
            offenders = stream
                    .filter(path -> path.toString().endsWith("System.java"))
                    .filter(SingletonTickingSystemStateGuardTest::containsTickingSystem)
                    .filter(SingletonTickingSystemStateGuardTest::containsSharedNextSweepField)
                    .toList();
        }

        assertTrue(offenders.isEmpty(), "Shared nextSweepAtMs fields in registered ticking systems: " + offenders);
    }

    private static boolean containsTickingSystem(Path path) {
        return read(path).contains("extends TickingSystem<");
    }

    private static boolean containsPlainSharedHashMapField(Path path) {
        String source = read(path);
        return source.contains("private final Map<") && source.contains("new HashMap<");
    }

    private static boolean containsSharedNextSweepField(Path path) {
        return read(path).contains("private long nextSweepAtMs;");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
```

- [ ] **Step 2: Run guard to verify it fails**

Run:

```powershell
.\mvnw -Dtest=SingletonTickingSystemStateGuardTest test
```

Expected: FAIL listing current systems with `HashMap` fields and shared `nextSweepAtMs`.

- [ ] **Step 3: Refactor `CompanionNeedsSystem` to per-store state**

Modify `CompanionNeedsSystem` fields:

```java
private final TameworkUiMessageService uiMessageService = new TameworkUiMessageService();
private final StoreScopedState<NeedsTickState> statesByStore = new StoreScopedState<>(NeedsTickState::new);
```

Add import:

```java
import com.alechilles.alecstamework.util.StoreScopedState;
```

Replace the beginning of `tick`:

```java
NeedsTickState tickState = statesByStore.get(store);
CompanionRuntimeClock.advanceByDeltaSeconds(dt);
long nowMs = System.currentTimeMillis();
boolean runNeedsSweep = nowMs >= tickState.nextSweepAtMs;
if (runNeedsSweep) {
    tickState.nextSweepAtMs = nowMs + SYSTEM_SWEEP_INTERVAL_MS;
}
```

Change notification calls:

```java
notifyOwnersOfMalnourishedLinkedNpcs(store, tickState, starvingLinkedByOwner, nowMs);
```

Update helper signatures and field access:

```java
private void notifyOwnersOfMalnourishedLinkedNpcs(@Nonnull Store<EntityStore> store,
                                                  @Nonnull NeedsTickState tickState,
                                                  @Nonnull Map<UUID, Integer> starvingLinkedByOwner,
                                                  long nowMs) {
    for (Map.Entry<UUID, Integer> entry : starvingLinkedByOwner.entrySet()) {
        UUID ownerId = entry.getKey();
        int count = entry.getValue() != null ? entry.getValue() : 0;
        if (ownerId == null || count <= 0 || !shouldSendMalnourishmentWarning(tickState, ownerId, nowMs)) {
            continue;
        }
        Player player = resolveOnlinePlayer(store, ownerId);
        if (player == null) {
            continue;
        }
        String message = count + MALNOURISHMENT_WARNING_SUFFIX;
        if (uiMessageService.show(player, message, NotificationStyle.Danger)) {
            tickState.lastMalnourishmentWarningByOwner.put(ownerId, nowMs);
        }
    }
}

private boolean shouldSendMalnourishmentWarning(@Nonnull NeedsTickState tickState,
                                                @Nonnull UUID ownerId,
                                                long nowMs) {
    Long lastSentMs = tickState.lastMalnourishmentWarningByOwner.get(ownerId);
    return lastSentMs == null || nowMs - lastSentMs >= MALNOURISHMENT_WARNING_THROTTLE_MS;
}

private void pruneWarningThrottleEntries(@Nonnull NeedsTickState tickState, long nowMs) {
    if (tickState.lastMalnourishmentWarningByOwner.isEmpty()) {
        return;
    }
    tickState.lastMalnourishmentWarningByOwner.entrySet().removeIf(entry -> {
        Long value = entry.getValue();
        return value == null || nowMs - value > MALNOURISHMENT_WARNING_PRUNE_WINDOW_MS;
    });
}

private static final class NeedsTickState {
    private long nextSweepAtMs;
    private final Map<UUID, Long> lastMalnourishmentWarningByOwner = new HashMap<>();
}
```

Update prune call:

```java
pruneWarningThrottleEntries(tickState, nowMs);
```

- [ ] **Step 4: Run focused checks**

Run:

```powershell
.\mvnw -Dtest=StoreScopedStateTest,SingletonTickingSystemStateGuardTest test
```

Expected: guard still FAILS because other systems remain, but `CompanionNeedsSystem` should no longer be in the offender list.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/systems/CompanionNeedsSystem.java src/test/java/com/alechilles/alecstamework/architecture/SingletonTickingSystemStateGuardTest.java
git commit -m "Fix: isolate companion needs system state per store" -m "Tests: .\mvnw -Dtest=StoreScopedStateTest,SingletonTickingSystemStateGuardTest test (guard expected to keep failing for remaining systems)"
```

---

### Task 3: Fix FlyingCompanionControlSystem Singleton State

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/FlyingCompanionControlSystem.java`

- [ ] **Step 1: Refactor fields**

Replace:

```java
private long nextSweepAtMs;
private final Map<Ref<EntityStore>, Vector3d> landingTargets = new HashMap<>();
private final Map<Ref<EntityStore>, String> debugSignatures = new HashMap<>();
```

With:

```java
private final StoreScopedState<FlyingTickState> statesByStore = new StoreScopedState<>(FlyingTickState::new);
```

Add import:

```java
import com.alechilles.alecstamework.util.StoreScopedState;
```

- [ ] **Step 2: Use per-store state in `tick`**

At the top of `tick`:

```java
FlyingTickState tickState = statesByStore.get(store);
long nowMs = System.currentTimeMillis();
if (nowMs < tickState.nextSweepAtMs) {
    return;
}
tickState.nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;
```

Replace every `landingTargets` access with `tickState.landingTargets`.
Replace every `debugSignatures` access with `tickState.debugSignatures`.

Add nested state:

```java
private static final class FlyingTickState {
    private long nextSweepAtMs;
    private final Map<Ref<EntityStore>, Vector3d> landingTargets = new HashMap<>();
    private final Map<Ref<EntityStore>, String> debugSignatures = new HashMap<>();
}
```

- [ ] **Step 3: Run guard**

Run:

```powershell
.\mvnw -Dtest=SingletonTickingSystemStateGuardTest test
```

Expected: guard still FAILS for remaining shared `nextSweepAtMs` systems, but not `FlyingCompanionControlSystem`.

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/systems/FlyingCompanionControlSystem.java
git commit -m "Fix: isolate flying companion control state per store" -m "Tests: .\mvnw -Dtest=SingletonTickingSystemStateGuardTest test (guard expected to keep failing for remaining systems)"
```

---

### Task 4: Fix Remaining Shared Sweep Throttles

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/CompanionDespawnProtectionSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/CompanionAttachmentSyncSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/CompanionPassiveBreedingSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/CompanionTranquilizerPeakSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandCoopResidentSyncSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandCoopManagedWildCaptureSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTeleportArrivalRelocationSystem.java`

- [ ] **Step 1: Apply the same per-store tick-state pattern**

For each file, replace:

```java
private long nextSweepAtMs;
```

With:

```java
private final StoreScopedState<TickState> statesByStore = new StoreScopedState<>(TickState::new);
```

Add nested state to each class:

```java
private static final class TickState {
    private long nextSweepAtMs;
}
```

At the top of each `tick` method, replace direct field reads/writes:

```java
TickState tickState = statesByStore.get(store);
long nowMs = System.currentTimeMillis();
if (nowMs < tickState.nextSweepAtMs) {
    return;
}
tickState.nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;
```

For `CommandTeleportArrivalRelocationSystem`, use its existing `SYSTEM_SWEEP_INTERVAL_MS` constant:

```java
tickState.nextSweepAtMs = nowMs + SYSTEM_SWEEP_INTERVAL_MS;
```

For `CompanionPassiveBreedingSystem`, preserve the disabled-reset behavior by setting:

```java
tickState.nextSweepAtMs = 0L;
```

- [ ] **Step 2: Run architecture guard**

Run:

```powershell
.\mvnw -Dtest=SingletonTickingSystemStateGuardTest test
```

Expected: PASS.

- [ ] **Step 3: Run thread-safety grep**

Run:

```powershell
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: no unsafe tick/runtime matches introduced by this task.

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/systems/CompanionDespawnProtectionSystem.java src/main/java/com/alechilles/alecstamework/npc/systems/CompanionAttachmentSyncSystem.java src/main/java/com/alechilles/alecstamework/npc/systems/CompanionPassiveBreedingSystem.java src/main/java/com/alechilles/alecstamework/npc/systems/CompanionTranquilizerPeakSystem.java src/main/java/com/alechilles/alecstamework/items/CommandCoopResidentSyncSystem.java src/main/java/com/alechilles/alecstamework/items/CommandCoopManagedWildCaptureSystem.java src/main/java/com/alechilles/alecstamework/items/CommandTeleportArrivalRelocationSystem.java
git commit -m "Fix: isolate periodic system throttles per store" -m "Tests: .\mvnw -Dtest=SingletonTickingSystemStateGuardTest test"
```

---

### Task 5: Move HStats Off Boot Thread And Add Timeouts

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/metrics/HStats.java`
- Modify: `src/main/java/com/alechilles/alecstamework/metrics/TameworkHStatsIntegration.java`
- Create: `src/test/java/com/alechilles/alecstamework/metrics/HStatsSourceSafetyTest.java`

- [ ] **Step 1: Write source safety test**

Create `src/test/java/com/alechilles/alecstamework/metrics/HStatsSourceSafetyTest.java`:

```java
package com.alechilles.alecstamework.metrics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HStatsSourceSafetyTest {
    private static final Path HSTATS = Path.of("src/main/java/com/alechilles/alecstamework/metrics/HStats.java");

    @Test
    void requestsHaveConnectAndReadTimeouts() throws IOException {
        String source = Files.readString(HSTATS);

        assertTrue(source.contains("setConnectTimeout("), "HStats requests must set connect timeout");
        assertTrue(source.contains("setReadTimeout("), "HStats requests must set read timeout");
    }

    @Test
    void recurringMetricsDoNotUseEngineSchedulerDirectly() throws IOException {
        String source = Files.readString(HSTATS);

        assertFalse(
                source.contains("HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate"),
                "HStats must not run blocking HTTP work on the engine scheduler"
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\mvnw -Dtest=HStatsSourceSafetyTest test
```

Expected: FAIL.

- [ ] **Step 3: Refactor `HStats`**

Change `HStats` declaration:

```java
public class HStats implements AutoCloseable {
```

Add fields:

```java
private static final int REQUEST_TIMEOUT_MS = 4_000;
private final ScheduledExecutorService metricsExecutor;
```

Add imports:

```java
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
```

In the constructor, replace direct calls and engine scheduling with:

```java
this.metricsExecutor = Executors.newSingleThreadScheduledExecutor(new HStatsThreadFactory());
this.metricsExecutor.execute(this::sendStartupMetrics);
this.metricsExecutor.scheduleAtFixedRate(this::logMetrics, 5, 5, TimeUnit.MINUTES);
```

Add:

```java
private void sendStartupMetrics() {
    logMetrics();
    addModToServer();
}

@Override
public void close() {
    metricsExecutor.shutdownNow();
}

private static final class HStatsThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(@Nonnull Runnable runnable) {
        Thread thread = new Thread(runnable, "AlecTamework-HStats");
        thread.setDaemon(true);
        return thread;
    }
}
```

In `sendRequest`, immediately after `openConnection()`:

```java
http.setConnectTimeout(REQUEST_TIMEOUT_MS);
http.setReadTimeout(REQUEST_TIMEOUT_MS);
```

- [ ] **Step 4: Hold and close the HStats instance**

In `TameworkHStatsIntegration`, add:

```java
private HStats hStats;
```

Replace:

```java
new HStats(TAMEWORK_HSTATS_UUID, version);
```

With:

```java
hStats = new HStats(TAMEWORK_HSTATS_UUID, version);
```

Add:

```java
public void close() {
    if (hStats != null) {
        hStats.close();
        hStats = null;
    }
}
```

Then wire `Tamework.stop()` or the existing shutdown path to call:

```java
if (hStatsIntegration != null) {
    hStatsIntegration.close();
}
```

- [ ] **Step 5: Run tests**

Run:

```powershell
.\mvnw -Dtest=HStatsSourceSafetyTest,TameworkDependencyMetricsReporterTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/metrics/HStats.java src/main/java/com/alechilles/alecstamework/metrics/TameworkHStatsIntegration.java src/test/java/com/alechilles/alecstamework/metrics/HStatsSourceSafetyTest.java
git commit -m "Fix: run HStats metrics with timeouts off engine threads" -m "Tests: .\mvnw -Dtest=HStatsSourceSafetyTest,TameworkDependencyMetricsReporterTest test"
```

---

### Task 6: Preserve Existing Profile State JSON Keys

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/persistence/sqlite/NpcProfileRepository.java`
- Create: `src/test/java/com/alechilles/alecstamework/persistence/sqlite/NpcProfileRepositoryStateMergeTest.java`

- [ ] **Step 1: Write regression tests**

Create `NpcProfileRepositoryStateMergeTest` using the same runtime setup pattern as existing SQLite repository tests. The two required tests are:

```java
@Test
void coopUpdatePreservesExistingTamedNameAndOwnerState() throws Exception {
    UUID npcUuid = UUID.randomUUID();
    UUID ownerUuid = UUID.randomUUID();

    repository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
            npcUuid,
            ownerUuid,
            "Clucky",
            "tamed_chicken",
            "Alec",
            "Clucky",
            true,
            null,
            null,
            null
    ));
    repository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
            npcUuid,
            null,
            null,
            null,
            null,
            null,
            null,
            "coop_chicken",
            2,
            null
    ));

    NpcProfileRepository.ProfileRecord profile = repository.loadProfileByNpcUuid(npcUuid);

    assertEquals(ownerUuid, profile.ownerUuid());
    assertEquals("Clucky", profile.displayName());
    assertEquals("tamed_chicken", profile.roleId());
    assertEquals("Alec", profile.ownerName());
    assertEquals("Clucky", profile.customName());
    assertEquals(Boolean.TRUE, profile.tamed());
    assertEquals("coop_chicken", profile.coopId());
    assertEquals(2, profile.coopSlot());
}

@Test
void nameUpdatePreservesExistingCoopState() throws Exception {
    UUID npcUuid = UUID.randomUUID();

    repository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
            npcUuid,
            null,
            null,
            "tamed_chicken",
            null,
            null,
            null,
            "coop_chicken",
            1,
            null
    ));
    repository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
            npcUuid,
            null,
            "Henrietta",
            null,
            "Alec",
            "Henrietta",
            true,
            null,
            null,
            null
    ));

    NpcProfileRepository.ProfileRecord profile = repository.loadProfileByNpcUuid(npcUuid);

    assertEquals("Henrietta", profile.displayName());
    assertEquals("Alec", profile.ownerName());
    assertEquals("Henrietta", profile.customName());
    assertEquals(Boolean.TRUE, profile.tamed());
    assertEquals("coop_chicken", profile.coopId());
    assertEquals(1, profile.coopSlot());
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
.\mvnw -Dtest=NpcProfileRepositoryStateMergeTest test
```

Expected: FAIL because partial updates replace `state_json`.

- [ ] **Step 3: Implement state merge**

In `NpcProfileRepository.upsertProfileInTransaction`, replace:

```java
JsonObject state = buildStateJson(update);
String stateJson = state.size() > 0 ? state.toString() : null;
if (stateJson == null && existingRow != null) {
    stateJson = existingRow.stateJson();
}
```

With:

```java
String stateJson = buildMergedStateJson(existingRow, update);
```

Add:

```java
@Nullable
private String buildMergedStateJson(@Nullable ExistingProfileRow existingRow,
                                    @Nonnull ProfileUpdate update) {
    JsonObject merged = existingRow != null ? parseJsonObject(trimToNull(existingRow.stateJson())) : null;
    if (merged == null) {
        merged = new JsonObject();
    }
    JsonObject updateState = buildStateJson(update);
    for (Map.Entry<String, JsonElement> entry : updateState.entrySet()) {
        merged.add(entry.getKey(), entry.getValue());
    }
    return merged.size() > 0 ? merged.toString() : null;
}
```

Add import if missing:

```java
import com.google.gson.JsonElement;
```

- [ ] **Step 4: Run tests**

Run:

```powershell
.\mvnw -Dtest=NpcProfileRepositoryStateMergeTest,TameworkPersistenceRuntimeMigrationTest,TameworkPersistenceRuntimeCloseTest test
```

Expected: PASS.

- [ ] **Step 5: Audit explicit clearing**

Run:

```powershell
rg "new NpcProfileRepository\\.ProfileUpdate" -n src/main/java src/test/java
```

Expected: list all call sites. For any call site intended to clear `coop_id`, `coop_slot`, `custom_name`, or `tamed`, record it in the commit message and create a follow-up task to add explicit clear flags to `ProfileUpdate`. Do not overload `null` to mean both absent and clear in this task.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/persistence/sqlite/NpcProfileRepository.java src/test/java/com/alechilles/alecstamework/persistence/sqlite/NpcProfileRepositoryStateMergeTest.java
git commit -m "Fix: merge NPC profile state updates" -m "Tests: .\mvnw -Dtest=NpcProfileRepositoryStateMergeTest,TameworkPersistenceRuntimeMigrationTest,TameworkPersistenceRuntimeCloseTest test"
```

---

### Task 7: Fix Spawner Duplication Ordering And Stack Guard

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java`
- Modify: `src/test/java/com/alechilles/alecstamework/items/SpawnerFeatureHandlerTest.java`

- [ ] **Step 1: Add source-order regression tests**

In `SpawnerFeatureHandlerTest`, add source-inspection tests:

```java
@Test
void spawnUpdatesHeldItemBeforeClearingCapturedSnapshot() throws Exception {
    String source = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"));

    int updateHeldItem = source.indexOf("playerInventoryService.updateHeldItem(player, updated)");
    int clearCapturedSnapshot = source.indexOf("linkedNpcSyncService.clearCapturedSnapshotIfPresent(capturedNpcUuid)");

    assertTrue(updateHeldItem >= 0, "spawn path must update held item");
    assertTrue(clearCapturedSnapshot >= 0, "spawn path must clear captured snapshot");
    assertTrue(updateHeldItem < clearCapturedSnapshot, "item consumption must happen before snapshot clear");
}

@Test
void captureRejectsStackedSpawnerItemsBeforeMetadataWrite() throws Exception {
    String source = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"));

    int quantityGuard = source.indexOf("itemStack.getQuantity() != 1");
    int capturedMetadata = source.indexOf(".withMetadata(TameworkMetadataKeys.CAPTURED");

    assertTrue(quantityGuard >= 0, "capture path must reject stacked spawner items");
    assertTrue(capturedMetadata >= 0, "capture path must write captured metadata");
    assertTrue(quantityGuard < capturedMetadata, "stack guard must run before captured metadata is stamped");
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
.\mvnw -Dtest=SpawnerFeatureHandlerTest test
```

Expected: FAIL.

- [ ] **Step 3: Add capture quantity guard**

In the capture path before building `updated` metadata:

```java
if (itemStack.getQuantity() != 1) {
    logSpawnerFlowDebug(
            "capture denied reason=stacked-spawner-item player=" + playerUuid
                    + " item=" + itemStack.getItemId()
                    + " quantity=" + itemStack.getQuantity()
    );
    return false;
}
```

- [ ] **Step 4: Reorder spawn consumption**

In `spawnFromItem`, compute `updated` before spawn. Perform:

```java
boolean updatedOk = hotbarSlot != null
        ? playerInventoryService.updateHotbarSlot(player, hotbarSlot, updated)
        : playerInventoryService.updateHeldItem(player, updated);
if (!updatedOk) {
    logger.at(Level.WARNING).log("Spawner spawn: failed to update held item.");
    logSpawnerFlowDebug("spawn denied reason=update-held-item-failed player=" + playerUuid + " item=" + itemStack.getItemId());
    return false;
}
```

before:

```java
Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(
        store,
        roleIndex,
        spawnPosition,
        rotation,
        null,
        null
);
```

Do not clear captured snapshots, coop snapshots, remap linked records, apply progression, or play effects until after spawn succeeds.

If spawn fails after the item was consumed, attempt one rollback:

```java
ItemStack rollback = itemStack;
boolean rollbackOk = hotbarSlot != null
        ? playerInventoryService.updateHotbarSlot(player, hotbarSlot, rollback)
        : playerInventoryService.updateHeldItem(player, rollback);
if (!rollbackOk) {
    logger.at(Level.WARNING).log("Spawner spawn: failed to roll back held item after spawn failure.");
}
return false;
```

- [ ] **Step 5: Run tests**

Run:

```powershell
.\mvnw -Dtest=SpawnerFeatureHandlerTest,SpawnerCaptureMetadataServiceTest,SpawnerNpcProgressionMetadataServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java src/test/java/com/alechilles/alecstamework/items/SpawnerFeatureHandlerTest.java
git commit -m "Fix: prevent spawner capture and spawn duplication paths" -m "Tests: .\mvnw -Dtest=SpawnerFeatureHandlerTest,SpawnerCaptureMetadataServiceTest,SpawnerNpcProgressionMetadataServiceTest test"
```

---

### Task 8: Fix Interaction Inventory Costs And Remainders

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/actions/InteractionInventoryEffects.java`
- Create or modify: `src/test/java/com/alechilles/alecstamework/npc/actions/InteractionInventoryEffectsTest.java`

- [ ] **Step 1: Write source-level regression tests**

Create `InteractionInventoryEffectsTest` if it does not exist:

```java
package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InteractionInventoryEffectsTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionInventoryEffects.java"
    );

    @Test
    void removeInventoryRequiresEmptyRemainderForSuccess() throws Exception {
        String source = Files.readString(SOURCE);

        assertFalse(
                source.contains("remainder.getQuantity() < quantity"),
                "Partial inventory removal must not count as full cost payment"
        );
        assertTrue(
                source.contains("remainder == null || remainder.isEmpty()"),
                "Removal success should require no remainder"
        );
    }

    @Test
    void addInventoryDoesNotSilentlyDropRemainder() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(
                source.contains("handleAddRemainder("),
                "Add inventory effects must explicitly handle the unadded remainder"
        );
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
.\mvnw -Dtest=InteractionInventoryEffectsTest test
```

Expected: FAIL.

- [ ] **Step 3: Require full cost removal**

In `applyRemoveItemsInventory`, replace:

```java
if (remainder == null || remainder.isEmpty() || remainder.getQuantity() < quantity) {
    applied = true;
}
```

With:

```java
if (remainder == null || remainder.isEmpty()) {
    applied = true;
}
```

If Hytale exposes an `allOrNothing` overload for `removeItemStack`, prefer:

```java
ItemStackTransaction transaction = container.removeItemStack(new ItemStack(item.getItem(), quantity), true);
```

and still require an empty remainder.

- [ ] **Step 4: Handle add remainders**

In `applyAddItemInventory`, after transaction:

```java
ItemStack remainder = transaction.getRemainder();
if (remainder == null || remainder.isEmpty()) {
    applied = true;
} else {
    handleAddRemainder(player, remainder);
    applied = true;
}
```

Add a helper that chooses the safest available behavior. If no world-drop API is already available in this class, fail the grant instead of destroying items:

```java
private boolean handleAddRemainder(@Nonnull Player player, @Nonnull ItemStack remainder) {
    return false;
}
```

Then wire the call as:

```java
if (remainder == null || remainder.isEmpty()) {
    applied = true;
} else if (handleAddRemainder(player, remainder)) {
    applied = true;
}
```

This preserves items by denying partial rewards until a world-drop helper is grounded against Hytale APIs.

- [ ] **Step 5: Run tests**

Run:

```powershell
.\mvnw -Dtest=InteractionInventoryEffectsTest,InteractionBehaviorTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/actions/InteractionInventoryEffects.java src/test/java/com/alechilles/alecstamework/npc/actions/InteractionInventoryEffectsTest.java
git commit -m "Fix: require complete interaction inventory costs" -m "Tests: .\mvnw -Dtest=InteractionInventoryEffectsTest,InteractionBehaviorTest test"
```

---

### Task 9: Guard Delayed UI World Execution

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java`
- Modify: `src/test/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPageNavigationTest.java`

- [ ] **Step 1: Add source regression test**

Add to `TameworkCommandSelectionPageNavigationTest`:

```java
@Test
void delayedRefreshGuardsWorldExecute() throws Exception {
    String source = Files.readString(Path.of(
            "src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java"
    ));

    assertTrue(source.contains("safeExecuteOnWorld("), "delayed refreshes should use safe world execution helper");
    assertTrue(source.contains("world.isAlive()"), "world execution helper should check world liveness");
    assertTrue(source.contains("catch (RuntimeException ignored)"), "world execution helper should absorb unload races");
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```powershell
.\mvnw -Dtest=TameworkCommandSelectionPageNavigationTest test
```

Expected: FAIL.

- [ ] **Step 3: Add safe execute helper**

In `TameworkCommandSelectionPage`, add:

```java
private static boolean safeExecuteOnWorld(@Nullable World world, @Nonnull Runnable task) {
    if (world == null || !world.isAlive()) {
        return false;
    }
    try {
        world.execute(task);
        return true;
    } catch (RuntimeException ignored) {
        return false;
    }
}
```

Replace delayed `world.execute(runnable)` calls in `scheduleRefreshTick` and `scheduleDebouncedFilterTextApply` with:

```java
safeExecuteOnWorld(world, () -> {
    if (ref == null || !ref.isValid()) {
        return;
    }
    refresh();
});
```

- [ ] **Step 4: Run tests**

Run:

```powershell
.\mvnw -Dtest=TameworkCommandSelectionPageNavigationTest,TameworkCommandSelectionPageLocalizationTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java src/test/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPageNavigationTest.java
git commit -m "Fix: guard delayed command page world execution" -m "Tests: .\mvnw -Dtest=TameworkCommandSelectionPageNavigationTest,TameworkCommandSelectionPageLocalizationTest test"
```

---

### Task 10: Defer Crash Telemetry Legacy Migration

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/metrics/CrashTelemetryService.java`
- Create or modify: `src/test/java/com/alechilles/alecstamework/metrics/CrashTelemetryServiceSourceTest.java`

- [ ] **Step 1: Add source safety test**

Create `CrashTelemetryServiceSourceTest`:

```java
package com.alechilles.alecstamework.metrics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrashTelemetryServiceSourceTest {
    @Test
    void legacyMigrationRunsOnDedicatedExecutor() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/metrics/CrashTelemetryService.java"
        ));

        assertTrue(source.contains("legacyMigrationExecutor"), "legacy migration must not block setup thread");
        assertTrue(source.contains("execute("), "legacy migration should be submitted asynchronously");
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```powershell
.\mvnw -Dtest=CrashTelemetryServiceSourceTest test
```

Expected: FAIL.

- [ ] **Step 3: Add dedicated migration executor**

In `CrashTelemetryService`, add:

```java
private final ExecutorService legacyMigrationExecutor;
```

Initialize it with a daemon single-thread executor:

```java
this.legacyMigrationExecutor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "AlecTamework-CrashTelemetryMigration");
    thread.setDaemon(true);
    return thread;
});
```

Replace synchronous migration in `create()` with:

```java
legacyMigrationExecutor.execute(() -> migrateLegacyTelemetryData(dataDirectory, serverRootDirectory, logger));
```

Add shutdown:

```java
public void close() {
    legacyMigrationExecutor.shutdownNow();
    // existing close work
}
```

- [ ] **Step 4: Run tests**

Run:

```powershell
.\mvnw -Dtest=CrashTelemetryServiceSourceTest,TameworkDebugCrashTelemetryCommandTest test
```

If `TameworkDebugCrashTelemetryCommandTest` does not exist, run:

```powershell
.\mvnw -Dtest=CrashTelemetryServiceSourceTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/metrics/CrashTelemetryService.java src/test/java/com/alechilles/alecstamework/metrics/CrashTelemetryServiceSourceTest.java
git commit -m "Fix: defer crash telemetry legacy migration" -m "Tests: .\mvnw -Dtest=CrashTelemetryServiceSourceTest test"
```

---

### Task 11: Changelog And Full Verification

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add changelog entries**

Under `## Unreleased` / `### Fixed`, add:

```markdown
- Fixed multi-world servers so periodic companion, coop, and teleport systems keep their sweep timers and runtime scratch state per world instead of sharing singleton system fields.
- Fixed HStats metrics reporting so network calls have timeouts and no longer run on server boot or engine scheduler threads.
- Fixed NPC profile persistence so partial profile updates preserve existing tamed, owner, name, and coop state fields.
- Fixed spawner capture and spawn flows to prevent stacked-capsule metadata duplication and spawn-before-consume duplication.
- Fixed custom interaction inventory effects so partial item removals no longer pay full costs and full-inventory rewards no longer silently discard items.
- Fixed command selection page refresh scheduling to ignore world-unload races instead of letting delayed refresh tasks die silently.
```

- [ ] **Step 2: Run targeted test set**

Run:

```powershell
.\mvnw -Dtest=StoreScopedStateTest,SingletonTickingSystemStateGuardTest,HStatsSourceSafetyTest,NpcProfileRepositoryStateMergeTest,SpawnerFeatureHandlerTest,InteractionInventoryEffectsTest,TameworkCommandSelectionPageNavigationTest,CrashTelemetryServiceSourceTest test
```

Expected: PASS.

- [ ] **Step 3: Run full suite**

Run:

```powershell
.\mvnw test
```

Expected: PASS.

- [ ] **Step 4: Run thread-safety grep**

Run:

```powershell
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: no unsafe tick/runtime player component access introduced by these fixes.

- [ ] **Step 5: Run diff checks**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors. Status should show only intended files plus any pre-existing unrelated dirty files.

- [ ] **Step 6: Commit changelog**

```powershell
git add CHANGELOG.md
git commit -m "Docs: document server stability fixes" -m "Tests: .\mvnw test"
```

---

## Self-Review

Spec coverage:
- Multi-world singleton ECS crash and shared throttles: Tasks 1-4.
- HStats boot/scheduler hang: Task 5.
- `state_json` partial update data loss: Task 6.
- Spawner reward-before-cost and stacked capture metadata: Task 7.
- Interaction inventory item loss and under-charge: Task 8.
- Delayed UI world execution: Task 9.
- Crash telemetry boot I/O: Task 10.
- Changelog and full verification: Task 11.

Known follow-up after Task 6:
- Audit whether any existing profile update needs to explicitly clear a state key. If yes, add explicit clear flags or a state patch object. Do not use `null` as both absent and delete.

Known follow-up after Task 8:
- If a grounded Hytale world-drop API is identified, replace the conservative "deny partial reward" behavior with "add what fits and drop remainder near player." Until then, preserving items by failing partial grants is safer than silently deleting the remainder.

Validation baseline:
- Always preserve unrelated dirty files.
- Run `.\mvnw test` before final release prep.
- Run the thread-safety grep before merging any ECS/tick-system task.
