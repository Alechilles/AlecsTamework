# Command Target HUD Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce server cost for the command-target HUD by limiting candidate iteration, lowering target-scan frequency, avoiding unnecessary linked-panel detail work, caching static display data, and smoothing multiplayer load.

**Architecture:** Keep the HUD server-authoritative and retain the existing visual/UI binder path. Move candidate discovery out of the per-tick player sweep and into the existing active-slot/inventory event gate, then make the tick service process only likely inspectors in a bounded round-robin loop. Split compact HUD snapshot building from linked-panel snapshot details so the HUD still reuses linked-panel controls but does not rebuild tooltip/detail strings it does not display.

**Tech Stack:** Java, Hytale ECS `TickingSystem`/`EntityEventSystem`, Tamework command item services, JUnit, Maven wrapper.

---

## File Structure

- Modify `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudActivationTracker.java`
  - Own active/dirty candidate registration, cached hand state, and candidate pruning decisions.
- Modify `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudActiveSlotSystem.java`
  - Register player candidates when selected hotbar slot changes.
- Modify `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudInventoryChangeSystem.java`
  - Register player candidates when hotbar contents change.
- Modify `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java`
  - Stop scanning all players every tick, add sweep/scan throttles, process bounded active candidates, cache static target display data, and keep hide behavior responsive.
- Modify `src/main/java/com/alechilles/alecstamework/items/CommandLoadedNpcStatusSnapshotService.java`
  - Add compact HUD snapshot options that skip linked-panel-only tooltip/detail work.
- Modify `src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelProgressionPresentationService.java`
  - Keep existing linked-panel behavior unchanged while allowing compact snapshot callers to omit expensive modifier tooltip construction.
- Modify `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudActivationTrackerTest.java`
  - Cover active candidate registration and pruning.
- Modify `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java`
  - Update timing assertions and cover bounded candidate processing helpers.
- Add or modify a focused test near existing status snapshot tests only if compact mode cannot be covered through existing package-private methods.

## Task 1: Active Inspector Registry

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudActivationTracker.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudActiveSlotSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudInventoryChangeSystem.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudActivationTrackerTest.java`

- [ ] **Step 1: Add failing tracker tests**

Add tests proving that `markDirty(playerUuid)` registers the player as a candidate, `recordResolvedHand(..., commandItem=true, ...)` keeps the player as a candidate, `recordResolvedHand(..., commandItem=false, ...)` removes the player after the inactive sanity window is satisfied, and `remove(playerUuid)` drops both hand state and candidate state.

Expected assertions:

```java
Assertions.assertTrue(tracker.candidatePlayerUuids().contains(PLAYER_UUID));
Assertions.assertFalse(tracker.candidatePlayerUuids().contains(PLAYER_UUID));
```

- [ ] **Step 2: Implement candidate ownership in the tracker**

Add a private ordered candidate set:

```java
private final LinkedHashSet<UUID> candidatePlayers = new LinkedHashSet<>();
```

Update `markDirty`, `recordResolvedHand`, and `remove` so dirty or active command-item players stay in `candidatePlayers`, inactive players are removed after a non-command hand is resolved, and null UUIDs are ignored.

Expose a package-private snapshot method:

```java
List<UUID> candidatePlayerUuids() {
    return List.copyOf(candidatePlayers);
}
```

- [ ] **Step 3: Keep event systems as the registration source**

The active-slot and inventory-change systems already call `markDirty(playerUuid)`. Leave their structure intact, but confirm they remain the only event systems needed for hotbar slot/content changes.

- [ ] **Step 4: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CommandTargetHudActivationTrackerTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandTargetHudActivationTracker.java src/main/java/com/alechilles/alecstamework/items/CommandTargetHudActiveSlotSystem.java src/main/java/com/alechilles/alecstamework/items/CommandTargetHudInventoryChangeSystem.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudActivationTrackerTest.java
git commit -m "Perf: track active command HUD inspectors"
```

## Task 2: Replace Per-Tick Player Sweep With Candidate Iteration

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java`

- [ ] **Step 1: Update tests to reject the hot sweep**

Update or replace `commandTargetHudRegistersInventoryEventGateSystems` with a source guard that fails if `CommandTargetHudService.tick()` still calls `store.forEachChunk(Query.and(playerType), ...)` as the normal path. Keep a small fallback sweep allowed only through a clearly named helper such as `seedCandidatesFromPlayerSweep`.

- [ ] **Step 2: Add candidate resolution helper**

In `CommandTargetHudService`, add a helper that resolves a candidate UUID to a live `Player` and `Ref<EntityStore>` through the current store/world-safe path available in this codebase. Do not use `Universe.getPlayers()` or `PlayerRef.getComponent(Player)`.

Preferred shape:

```java
@Nullable
private PlayerCandidate resolvePlayerCandidate(@Nonnull UUID playerUuid, @Nonnull Store<EntityStore> store) {
    Ref<EntityStore> playerRef = store.getEntityRef(playerUuid);
    if (playerRef == null || !playerRef.isValid()) {
        return null;
    }
    Player player = store.getComponent(playerRef, Player.getComponentType());
    return player != null ? new PlayerCandidate(playerUuid, player, playerRef) : null;
}
```

If `Store` does not expose `getEntityRef(UUID)`, use the smallest existing repo pattern for UUID-to-ref lookup and keep it inside this helper.

- [ ] **Step 3: Change `tick` to process candidates**

Change `tick` to:

1. return before work when the sweep interval has not elapsed,
2. read `activationTracker.candidatePlayerUuids()`,
3. process only those candidates through `updatePlayer`,
4. remove invalid candidates from tracker and HUD state,
5. avoid `clearInactivePlayers(activePlayers)` based on an all-player sweep.

- [ ] **Step 4: Add low-frequency fallback seeding**

Keep a fallback sweep that runs every 1-2 seconds to seed unknown players and recover missed events. It should only call the current player-chunk scan on that recovery cadence, not every service pass.

Suggested constants:

```java
private static final long SWEEP_INTERVAL_MS = 100L;
private static final long FALLBACK_DISCOVERY_INTERVAL_MS = 1_500L;
```

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CommandTargetHudServiceTest,CommandTargetHudActivationTrackerTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudActivationTrackerTest.java
git commit -m "Perf: inspect command HUD candidates only"
```

## Task 3: Tune Sweep And Target Scan Intervals

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java`

- [ ] **Step 1: Update interval tests**

Adjust the existing tests:

```java
Assertions.assertTrue(CommandTargetHudService.sweepIntervalMsForTests() >= 50L);
Assertions.assertTrue(CommandTargetHudService.sweepIntervalMsForTests() <= 100L);
Assertions.assertTrue(CommandTargetHudService.targetScanIntervalMsForTests() >= 200L);
Assertions.assertTrue(CommandTargetHudService.targetScanIntervalMsForTests() <= 250L);
```

Keep `refreshIntervalMsForTests()` at `5_000L`.

- [ ] **Step 2: Update constants**

Set:

```java
private static final long SWEEP_INTERVAL_MS = 100L;
private static final long TARGET_SCAN_INTERVAL_MS = 200L;
private static final long REFRESH_INTERVAL_MS = 5_000L;
```

- [ ] **Step 3: Confirm responsiveness paths bypass slow refresh**

Keep immediate scans when the held command item changes and immediate refresh when the target key changes. Do not make look-away/hide wait for the 5s full refresh interval.

- [ ] **Step 4: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CommandTargetHudServiceTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java
git commit -m "Perf: throttle command HUD target scans"
```

## Task 4: Compact Snapshot Mode For HUD

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandLoadedNpcStatusSnapshotService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelProgressionPresentationService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java`
- Test: existing snapshot/progression tests only if package-private option methods need coverage

- [ ] **Step 1: Add snapshot options**

Add a package-private record:

```java
record SnapshotOptions(boolean includeHappinessBreakdown, boolean includeProgressionModifierTooltip) {
    static SnapshotOptions linkedPanel() {
        return new SnapshotOptions(true, true);
    }

    static SnapshotOptions compactHud() {
        return new SnapshotOptions(false, false);
    }
}
```

- [ ] **Step 2: Thread options through `buildLoadedEntry`**

Keep the current method signature as a delegating overload:

```java
LinkedNpcEntry buildLoadedEntry(Player player, Ref<EntityStore> npcRef, Store<EntityStore> store, NpcStatusContext context) {
    return buildLoadedEntry(player, npcRef, store, context, SnapshotOptions.linkedPanel());
}
```

Add the options-aware overload and use `SnapshotOptions.compactHud()` from `CommandTargetHudService.buildModel`.

- [ ] **Step 3: Skip unused HUD detail work**

In compact mode:

- pass `null` for happiness modifier breakdown instead of calling `buildHappinessModifierBreakdown`,
- pass `null` as the level future-stat modifier tooltip instead of calling `buildModifierTooltip`,
- still read health, happiness current/max/target, needs, level progress, talent points, traits, breeding cooldown, and harvest cooldown because those are visible in the HUD.

- [ ] **Step 4: Add only focused tests if needed**

If this can be tested without heavy game object setup, add package-private static/helper tests proving compact mode returns `includeHappinessBreakdown=false` and `includeProgressionModifierTooltip=false`. Do not add brittle source-order tests for this task unless no better seam exists.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CommandTargetHudServiceTest,CommandLinkedPanelEntryServiceHappinessTooltipTest,CommandLinkedPanelProgressionPresentationServiceTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandLoadedNpcStatusSnapshotService.java src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelProgressionPresentationService.java src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java src/test/java/com/alechilles/alecstamework/items
git commit -m "Perf: use compact command HUD snapshots"
```

## Task 5: Cache Static Target Display Data

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java` only for cache key/expiry helpers

- [ ] **Step 1: Define static cache entry**

Add a small cache inside `CommandTargetHudService`:

```java
private final Map<StaticTargetCacheKey, StaticTargetDisplay> staticTargetCache = new HashMap<>();
private static final long STATIC_DISPLAY_CACHE_MS = 30_000L;
```

Cache key fields:

```java
private record StaticTargetCacheKey(UUID npcUuid, String language, String roleId, boolean tamed) {}
```

Cached values:

```java
private record StaticTargetDisplay(
        CommandTargetHudViewModel.FoodRow favoriteFood,
        List<CommandTargetHudViewModel.FoodRow> foodRows,
        List<CommandTargetHudViewModel.AttachmentRow> attachmentRows,
        CommandTargetHudViewModel.TameRequirementRow tameRequirement,
        String ownerDisplayName,
        long expiresAtMs
) {}
```

- [ ] **Step 2: Move static builders behind cache lookup**

In `buildModel`, keep `loadedSnapshotService.buildLoadedEntry(...)` dynamic. Move food rows, attachment rows, tame requirement rows, and owner display name into a `resolveStaticTargetDisplay(...)` helper.

- [ ] **Step 3: Keep cache conservative**

Expire after 30 seconds. Clear entries when `npcRef` is invalid, when `modelAssetId` or attachment map is missing and the current NPC has attachments, or when role/tamed/language changes through the cache key. Do not cache health, needs, cooldowns, level, or trait roll values.

- [ ] **Step 4: Add narrow helper tests only if practical**

If cache key and expiration are pure helpers, test:

```java
Assertions.assertTrue(CommandTargetHudService.isStaticDisplayCacheValidForTests(1_000L, 1_500L, 30_000L));
Assertions.assertFalse(CommandTargetHudService.isStaticDisplayCacheValidForTests(1_000L, 31_001L, 30_000L));
```

Do not add tests that require constructing live Hytale NPC refs just to exercise the cache.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CommandTargetHudServiceTest,CommandTargetHudViewModelTest,CommandTargetHudBinderTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java
git commit -m "Perf: cache static command HUD display data"
```

## Task 6: Round-Robin Active Inspector Processing

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java`

- [ ] **Step 1: Add pure helper tests**

Add tests for a helper that rotates candidate order and caps processed candidates:

```java
List<UUID> selected = CommandTargetHudService.selectCandidatesForPassForTests(
        List.of(A, B, C, D),
        2,
        1
);
Assertions.assertEquals(List.of(B, C), selected);
```

Also cover wraparound:

```java
Assertions.assertEquals(List.of(D, A), CommandTargetHudService.selectCandidatesForPassForTests(List.of(A, B, C, D), 2, 3));
```

- [ ] **Step 2: Add processing cap constants**

Add:

```java
private static final int MAX_CANDIDATES_PER_PASS = 4;
private int nextCandidateOffset;
```

Four candidates per 100ms is enough for typical play and prevents all active players from raycasting in the same pass.

- [ ] **Step 3: Process candidate slices**

In `tick`, select a bounded slice from `activationTracker.candidatePlayerUuids()`, process those candidates, and advance `nextCandidateOffset`. If the candidate list shrinks, clamp the offset.

- [ ] **Step 4: Preserve immediate local responsiveness**

Dirty candidates should be prioritized before non-dirty active candidates. If needed, expose `activationTracker.isDirty(uuid)` and sort the current pass so recently changed hand state gets inspected first.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CommandTargetHudServiceTest,CommandTargetHudActivationTrackerTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java src/test/java/com/alechilles/alecstamework/items/CommandTargetHudServiceTest.java
git commit -m "Perf: spread command HUD inspections across ticks"
```

## Task 7: Verification And Profiling Follow-Up

**Files:**
- Modify: none unless tests expose a real issue

- [ ] **Step 1: Run runtime safety grep**

Run:

```powershell
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: no new unsafe player access in HUD tick/event paths.

- [ ] **Step 2: Run architecture guards touched by tick-path work**

Run:

```powershell
.\mvnw.cmd -Dtest=EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Expected: pass.

- [ ] **Step 3: Run focused HUD tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CommandTargetHudServiceTest,CommandTargetHudActivationTrackerTest,CommandTargetHudViewModelTest,CommandTargetHudBinderTest,TameworkCommandTargetHudTest test
```

Expected: pass.

- [ ] **Step 4: Run full tests**

Run:

```powershell
.\mvnw.cmd test
```

Expected: pass.

- [ ] **Step 5: Optional in-game verification**

In a multiplayer or simulated multiplayer session, hold command items on multiple players and confirm:

- HUD appears within about 200ms after aiming at a supported NPC,
- HUD hides within about 200ms after looking away or switching off the command item,
- target changes update without waiting for the 5s model refresh,
- Spark no longer shows `CommandTargetHudService.tick()` dominated by per-tick `Store.forEachChunk()`.

- [ ] **Step 6: Commit verification-only changes if any**

Only commit if a real fix was required during verification. Otherwise, do not create an empty commit.

## Self-Review

- Spec coverage: all six requested optimizations are covered by Tasks 1-6, with verification in Task 7.
- Tests are limited to behavior that can silently regress: candidate tracking, service timing/caps, compact snapshot options, and pure cache expiry helpers.
- The plan avoids changing the HUD UI layout, localization, or food/attachment display semantics.
- Runtime safety checks are included because the work changes tick-path player resolution.
