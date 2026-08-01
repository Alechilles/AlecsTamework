# Bonded Roster Panel Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make bonded roster loading and lifecycle changes prompt, prevent unchanged refresh packets from swallowing clicks, and live-project active level, current XP, and available talent points with a five-second page-wide throttle.

**Architecture:** Add cache publication notifications and a scoped page signal source, then move timing decisions into a small UI refresh coordinator. The page continues to own rendering and navigation, while live bonded presentation overlays current progression from the exact active entity and the renderer sends only meaningful property or binding changes.

**Tech Stack:** Java 25, Hytale server custom UI/ECS APIs indexed at release 0.5.7, Maven Wrapper, JUnit 5.

## Global Constraints

- A custom-page `sendUpdate(...)` acknowledgement gate is page-wide; packet frequency is the primary input-responsiveness control.
- Progression updates are throttled across the whole open page to at most one render every 5,000 ms.
- Countdown presentation updates every 10,000 ms normally, every 1,000 ms during the final 10,000 ms, and once at expiration.
- The fallback safety refresh runs every 30,000 ms and sends no packet when the rendered model is unchanged.
- Capture, summon, dismiss, revive, abandonment, authoritative invalidation, cache publication, and in-page mutations request an immediate refresh.
- `ACTIVE` companions may use exact-current-world live progression; `STORED` and `DEAD` companions use durable snapshot progression.
- Failed live progression resolution preserves durable values and never substitutes zero.
- ECS reads and UI sends happen on the current world thread; async callbacks carry only stable identifiers and page-generation state.
- Preserve revision fencing and action trust. Do not enable an action from an untrusted snapshot.
- Do not modify or stage the user's existing Avatar Flight files.

---

## File Structure

### New files

- `src/main/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshSignal.java`: immutable immediate/progression signal contract passed into an open page.
- `src/main/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshSignalSource.java`: scoped subscription interface with a no-op implementation for generic rosters and compatibility constructors.
- `src/main/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshCoordinator.java`: owns five-second progression throttling, countdown deadlines, 30-second safety wakes, coalescing, and cancellation tokens.
- `src/test/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshCoordinatorTest.java`: deterministic clock/scheduler tests for the timing contract.
- `src/test/java/com/alechilles/alecstamework/ui/LinkedNpcPanelCardRenderStateTest.java`: dynamic-versus-structural card change classification.
- `src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelRefreshSignalSource.java`: combines one roster's cache notifications and owner XP events into UI signals.
- `src/test/java/com/alechilles/alecstamework/items/BondedCompanionPanelRefreshSignalSourceTest.java`: subscription filtering and closure tests.

### Modified files

- `BondedCompanionPanelSnapshotCache.java` and its test: scoped state-change subscribers, notified outside cache locks.
- `BondedCompanionPanelEntrySourceService.java` and `BondedCompanionPanelLifecycle.java`: forward scoped cache subscriptions and project live progression.
- `BondedCompanionPanelLiveProfileOverlay.java` and its test: immutable live progression attribute overlay.
- `CommandSelectionPageService.java`, `CommandItemFeatureHandler.java`, and `Tamework.java`: wire the scoped cache/XP signal source into bonded pages.
- `TameworkCommandSelectionPage.java`: replace the fixed one-second loop, close subscriptions, dispatch coordinator wakes, and skip empty updates.
- `LinkedNpcPanelCardRenderState.java`, `LinkedNpcPanelFeatureController.java`, `LinkedNpcPanelGroupAssignOverlayState.java`, and their focused tests: distinguish dynamic property changes from binding/overlay changes.
- `docs/Command-Items.md`, `wiki/Player-Guides/Companion-Controls/Linked-Panel-Guide.md`, and `CHANGELOG.md`: document final player-visible behavior.

---

### Task 1: Cache Publication Notifications

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelSnapshotCache.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelEntrySourceService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelLifecycle.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/BondedCompanionPanelSnapshotCacheTest.java`

**Interfaces:**
- Produces: `AutoCloseable subscribe(UUID ownerUuid, String rosterId, Runnable listener)` on the cache, entry source, and lifecycle.
- Semantics: notify after authoritative invalidation, successful publication, and terminal load failure; never invoke a listener while holding the cache lock.

- [ ] **Step 1: Write failing cache subscriber tests**

Add tests that subscribe before a load, verify exactly one callback after publication, verify invalidation and replacement publication each notify, verify failure notifies, and verify closing the returned subscription suppresses later callbacks.

```java
AtomicInteger notifications = new AtomicInteger();
AutoCloseable subscription = cache.subscribe(OWNER, ROSTER,
        notifications::incrementAndGet);
cache.peek(OWNER, ROSTER);
worker.runNext();
api.completeNext(List.of(profile("ready", 1L)));
assertEquals(1, notifications.get());
subscription.close();
api.fire(change(OWNER, "ready", 2L));
assertEquals(1, notifications.get());
```

- [ ] **Step 2: Run the cache tests and confirm failure**

Run: `./mvnw -Dtest=BondedCompanionPanelSnapshotCacheTest test`

Expected: compilation fails because `subscribe(UUID, String, Runnable)` does not exist.

- [ ] **Step 3: Implement scoped notification storage**

Use a key-to-listener map guarded by the existing lock. Capture a listener snapshot while changing cache state, release the lock, and invoke each listener with isolated `RuntimeException` handling.

```java
AutoCloseable subscribe(UUID ownerUuid, String rosterId, Runnable listener) {
    Key key = new Key(ownerUuid, rosterId);
    synchronized (lock) {
        if (closed) return () -> { };
        listeners.computeIfAbsent(key, ignored -> new ArrayList<>()).add(listener);
    }
    return () -> removeListener(key, listener);
}
```

Notify from `changed(...)`, `published(...)`, and `failed(...)`; clear listeners during owner eviction and cache close. Forward the same method through `BondedCompanionPanelEntrySourceService` and `BondedCompanionPanelLifecycle`.

- [ ] **Step 4: Run focused cache/lifecycle tests**

Run: `./mvnw -Dtest=BondedCompanionPanelSnapshotCacheTest,BondedCompanionPanelLifecycleIntegrationTest test`

Expected: PASS.

- [ ] **Step 5: Commit the cache notification seam**

```bash
git add src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelSnapshotCache.java \
  src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelEntrySourceService.java \
  src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelLifecycle.java \
  src/test/java/com/alechilles/alecstamework/items/BondedCompanionPanelSnapshotCacheTest.java
git commit -m "Feat: notify bonded roster cache publication"
```

### Task 2: Live Active Progression Projection

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelLiveProfileOverlay.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelEntrySourceService.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/BondedCompanionPanelLiveProfileOverlayTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/BondedCompanionPanelCachedEntrySourceTest.java`

**Interfaces:**
- Produces: `BondedCompanionPanelLiveProfileOverlay.ProgressionSnapshot` containing `levelingConfigId`, `level`, `currentXp`, nullable `talentConfigId`, and nullable `Integer talentSpentPoints`.
- Produces: `withProgression(BondedCompanionProfileView, @Nullable ProgressionSnapshot)` preserving the original profile when the snapshot is absent or identical.
- Consumes: `CompanionLevelingService.resolveSnapshot(...)`, `TameworkTalentsComponent`, and `CompanionTalentService.resolveTalentConfig(...)` from the exact active entity.

- [ ] **Step 1: Write failing immutable overlay tests**

Cover replacement of live level/current XP/talent spent points, unchanged identity/revision/state, no-op equality, and a null projection retaining durable values.

```java
var updated = BondedCompanionPanelLiveProfileOverlay.withProgression(profile,
        new ProgressionSnapshot("wyvern-leveling", 12, 43.5,
                "wyvern-talents", 3));
assertEquals("12", updated.snapshotPresentationData().get("level"));
assertEquals("43.5", updated.snapshotPresentationData().get("currentXp"));
assertEquals("3", updated.snapshotPresentationData().get("talentSpentPoints"));
```

- [ ] **Step 2: Run the overlay tests and confirm failure**

Run: `./mvnw -Dtest=BondedCompanionPanelLiveProfileOverlayTest test`

Expected: compilation fails because `ProgressionSnapshot` and `withProgression` do not exist.

- [ ] **Step 3: Implement the overlay and exact live reader**

Add `withProgression(...)` using a copied `LinkedHashMap`. In `BondedCompanionPanelEntrySourceService.withLivePresentation(...)`, resolve the live entity only when the active lease world equals the player's current world, then read the leveling snapshot and talents component.

```java
private ProgressionSnapshot liveProgression(Player player,
        Store<EntityStore> store, BondedCompanionProfileView profile) {
    Ref<EntityStore> npcRef = exactActiveReference(player, profile);
    var leveling = CompanionLevelingService.resolveSnapshot(
            npcRef, store, profile.roleId());
    if (leveling == null) return null;
    TameworkTalentsComponent talents = safeTalents(npcRef, store);
    return new ProgressionSnapshot(leveling.configId(), leveling.level(),
            leveling.currentXp(), talentConfigId(npcRef, store),
            talents == null ? 0 : talents.getSpentPoints());
}
```

If talent state/config cannot be resolved, preserve those durable talent fields while still applying valid leveling fields; represent this with nullable talent fields in the snapshot rather than fabricated zeroes.

- [ ] **Step 4: Run focused bonded presentation tests**

Run: `./mvnw -Dtest=BondedCompanionPanelLiveProfileOverlayTest,BondedCompanionPanelCachedEntrySourceTest,BondedCompanionPanelFeaturePresentationSourceTest test`

Expected: PASS.

- [ ] **Step 5: Commit live progression projection**

```bash
git add src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelLiveProfileOverlay.java \
  src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelEntrySourceService.java \
  src/test/java/com/alechilles/alecstamework/items/BondedCompanionPanelLiveProfileOverlayTest.java \
  src/test/java/com/alechilles/alecstamework/items/BondedCompanionPanelCachedEntrySourceTest.java
git commit -m "Fix: project active bonded progression"
```

### Task 3: Deterministic Refresh Coordinator

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshSignal.java`
- Create: `src/main/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshSignalSource.java`
- Create: `src/main/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshCoordinator.java`
- Create: `src/test/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshCoordinatorTest.java`

**Interfaces:**
- Produces: `LinkedPanelRefreshSignal.Kind.IMMEDIATE` and `PROGRESSION`.
- Produces: coordinator methods `start()`, `request(Kind)`, `recordRendered(boolean progressionIncluded, long shortestCountdownRemainingMs)`, and `close()`.
- Consumes: injected millisecond clock, delayed scheduler, and refresh callback; production adapts `CompletableFuture.delayedExecutor(...)`.

- [ ] **Step 1: Write failing deterministic scheduling tests**

Use a manual clock and queued scheduler to assert:

- immediate signals coalesce at zero delay;
- progression signals refresh immediately when the prior progression render is at least 5,000 ms old;
- repeated progression signals within the window create one callback at the boundary;
- a render containing progression resets the five-second window;
- countdown delays are 10,000 ms above ten seconds, 1,000 ms within the final ten seconds, and exact remaining time below one second;
- safety wakes occur every 30,000 ms;
- close invalidates queued callbacks.

```java
coordinator.recordRendered(true, 0L);
clock.set(1_000L);
coordinator.request(PROGRESSION);
coordinator.request(PROGRESSION);
assertEquals(List.of(4_000L), scheduler.delays());
clock.set(5_000L);
scheduler.runDue();
assertEquals(1, refreshes.get());
```

- [ ] **Step 2: Run the coordinator test and confirm failure**

Run: `./mvnw -Dtest=LinkedPanelRefreshCoordinatorTest test`

Expected: compilation fails because the coordinator types do not exist.

- [ ] **Step 3: Implement generation-fenced coalescing**

Keep one pending immediate token, one progression token, one countdown token, and one safety token. Each scheduled callback compares its captured version and closed state before invoking the refresh callback.

```java
void request(Kind kind) {
    if (closed) return;
    if (kind == IMMEDIATE) scheduleImmediate();
    else scheduleProgression(Math.max(0L,
            lastProgressionRenderMs + PROGRESSION_INTERVAL_MS - clock.getAsLong()));
}
```

The coordinator schedules only; it never reads ECS state or calls `sendUpdate` directly.

- [ ] **Step 4: Run coordinator tests**

Run: `./mvnw -Dtest=LinkedPanelRefreshCoordinatorTest test`

Expected: PASS.

- [ ] **Step 5: Commit refresh policy and coordinator**

```bash
git add src/main/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshSignal.java \
  src/main/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshSignalSource.java \
  src/main/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshCoordinator.java \
  src/test/java/com/alechilles/alecstamework/ui/LinkedPanelRefreshCoordinatorTest.java
git commit -m "Refactor: coordinate linked panel refresh timing"
```

### Task 4: Scoped Cache and XP Signals

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelRefreshSignalSource.java`
- Create: `src/test/java/com/alechilles/alecstamework/items/BondedCompanionPanelRefreshSignalSourceTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandSelectionPageService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandItemFeatureHandler.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`

**Interfaces:**
- Consumes: cache lifecycle `subscribe(owner, roster, Runnable)` and nullable `TameworkEventsApi`.
- Produces: `LinkedPanelRefreshSignalSource forRoster(UUID ownerUuid, String rosterId)`.
- Cache callbacks emit `IMMEDIATE`; `CompanionXpAwardedEvent` with matching non-null owner emits `PROGRESSION`.

- [ ] **Step 1: Write failing signal-source tests**

Use fake lifecycle and event subscriptions to prove matching-owner XP signals pass, other-owner events are ignored, cache callbacks are immediate, and closing the composite subscription closes both children exactly once.

```java
source.forRoster(OWNER, ROSTER).subscribe(signals::add);
events.emit(xpEvent(OWNER, NPC_ONE));
events.emit(xpEvent(OTHER_OWNER, NPC_TWO));
cacheListener.run();
assertEquals(List.of(PROGRESSION, IMMEDIATE), kinds(signals));
```

- [ ] **Step 2: Run the signal-source test and confirm failure**

Run: `./mvnw -Dtest=BondedCompanionPanelRefreshSignalSourceTest test`

Expected: compilation fails because the scoped source does not exist.

- [ ] **Step 3: Implement and wire the scoped source**

Add a nullable events parameter only at the terminal `CommandItemFeatureHandler` constructor, preserve older overloads by delegating `null`, and pass `api.events()` from `Tamework`. Give `CommandSelectionPageService` the signal factory and pass `LinkedPanelRefreshSignalSource.none()` for generic rosters.

```java
LinkedPanelRefreshSignalSource pageSignals = config.usesBondedCompanionRoster()
        ? bondedRefreshSignals.forRoster(player.getUuid(),
                config.getBondedRosterId())
        : LinkedPanelRefreshSignalSource.none();
```

- [ ] **Step 4: Run routing and signal tests**

Run: `./mvnw -Dtest=BondedCompanionPanelRefreshSignalSourceTest,BondedCompanionCommandPageRoutingIntegrationTest test`

Expected: PASS.

- [ ] **Step 5: Commit scoped signal wiring**

```bash
git add src/main/java/com/alechilles/alecstamework/Tamework.java \
  src/main/java/com/alechilles/alecstamework/items/BondedCompanionPanelRefreshSignalSource.java \
  src/main/java/com/alechilles/alecstamework/items/CommandSelectionPageService.java \
  src/main/java/com/alechilles/alecstamework/items/CommandItemFeatureHandler.java \
  src/test/java/com/alechilles/alecstamework/items/BondedCompanionPanelRefreshSignalSourceTest.java
git commit -m "Feat: wake bonded roster pages from changes"
```

### Task 5: Replace the One-Second Page Heartbeat

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/LinkedNpcPanelCardRenderState.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/LinkedNpcPanelFeatureController.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/LinkedNpcPanelGroupAssignOverlayState.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPageNavigationTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/BondedCompanionCardPresenterTest.java`
- Create: `src/test/java/com/alechilles/alecstamework/ui/LinkedNpcPanelCardRenderStateTest.java`

**Interfaces:**
- Consumes: `LinkedPanelRefreshSignalSource` and `LinkedPanelRefreshCoordinator`.
- Produces: page refresh attempts dispatched through `CommandPageWorldDispatcher`.
- Produces: a boolean result from rendering that distinguishes a sent update from a no-op and reports the shortest visible countdown to the coordinator.

- [ ] **Step 1: Add failing source/behavior assertions**

Extend navigation/architecture tests to assert the fixed `LINKED_PANEL_REFRESH_INTERVAL_MS` loop is gone, the page closes its refresh subscription/coordinator during close and navigation, and label-only `DYNAMIC` updates do not call `bindBondedCardEvents(...)`.

Add render-state tests proving unchanged entries/features return `NONE`, progression/current-XP-only bonded changes return `DYNAMIC`, and action state/revision/structure changes return `FULL`.

```java
assertEquals(Update.DYNAMIC, state.updateAt(0,
        entries, null, featuresWithCurrentXp("45")));
assertEquals(Update.FULL, state.updateAt(0,
        entries, null, featuresWithActionEnabled(false)));
```

- [ ] **Step 2: Run page/render-state tests and confirm failure**

Run: `./mvnw -Dtest=TameworkCommandSelectionPageNavigationTest,LinkedNpcPanelCardRenderStateTest,BondedCompanionCardPresenterTest test`

Expected: at least the heartbeat/removal and progression classification assertions fail.

- [ ] **Step 3: Integrate coordinator and subscription lifecycle**

Construct the coordinator in the page, subscribe after initial build, and route callbacks to the current world thread. Remove `startRefreshLoop`, `scheduleRefreshTick`, and the fixed one-second constant. All existing successful in-page mutations request `IMMEDIATE`.

```java
private void onRefreshSignal(LinkedPanelRefreshSignal signal) {
    refreshCoordinator.request(signal.kind());
}

private void runRefreshOnWorldThread() {
    if (dismissed || !isCurrentLinkedPanelOwner()) return;
    refreshLinkedNpcEntries();
    RefreshOutcome outcome = sendCardRefreshUpdate();
    refreshCoordinator.recordRendered(outcome.progressionIncluded(),
            outcome.shortestCountdownRemainingMs());
}
```

- [ ] **Step 4: Suppress no-op commands and stable rebinding**

Track overlay revisions so `applyTo(...)` runs only after overlay state changes. Remove option, close, panel-control, and unchanged card event rebinding from periodic updates. Bind events during initial build, structural card rebuilds, or `FULL` card changes only. Before sending, inspect both builders:

```java
if (commandBuilder.getCommands().length == 0
        && eventBuilder.getEvents().length == 0) {
    return RefreshOutcome.notSent(shortestCountdown);
}
sendUpdate(commandBuilder, eventBuilder, false);
return RefreshOutcome.sent(shortestCountdown);
```

Ensure dynamic card comparison includes `level`, `currentXp`,
`levelingConfigId`, `talentConfigId`, and `talentSpentPoints`, while action
status, revision, identity, and layout-affecting fields still force `FULL`.

- [ ] **Step 5: Run focused UI tests**

Run: `./mvnw -Dtest=TameworkCommandSelectionPageNavigationTest,LinkedNpcPanelCardRenderStateTest,BondedCompanionCardPresenterTest,BondedCompanionPanelFeatureBinderTest test`

Expected: PASS.

- [ ] **Step 6: Commit page refresh integration**

```bash
git add src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java \
  src/main/java/com/alechilles/alecstamework/ui/LinkedNpcPanelCardRenderState.java \
  src/main/java/com/alechilles/alecstamework/ui/LinkedNpcPanelFeatureController.java \
  src/main/java/com/alechilles/alecstamework/ui/LinkedNpcPanelGroupAssignOverlayState.java \
  src/test/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPageNavigationTest.java \
  src/test/java/com/alechilles/alecstamework/ui/LinkedNpcPanelCardRenderStateTest.java \
  src/test/java/com/alechilles/alecstamework/ui/BondedCompanionCardPresenterTest.java
git commit -m "Fix: avoid redundant linked panel updates"
```

### Task 6: Documentation and Complete Verification

**Files:**
- Modify: `docs/Command-Items.md`
- Modify: `wiki/Player-Guides/Companion-Controls/Linked-Panel-Guide.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Documents the final shipped behavior only: prompt loading, reliable controls, coalesced live active progression, and durable stored/dead progression.

- [ ] **Step 1: Update technical and player-facing documentation**

Change the durable-only progression sentence in `docs/Command-Items.md` to:

```markdown
Active bonded rows project current level, XP, and available talent points from
their exact live companion, coalesced to avoid interrupting panel controls.
Stored and dead rows continue to use their durable progression snapshot.
```

Add equivalent player-language guidance to the linked-panel wiki. Add one
`### Changed` changelog bullet describing faster roster appearance, reliable
clicks, and live active progression; do not create a new changelog version.

- [ ] **Step 2: Run documentation checks**

Run from Git Bash:

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File ./scripts/tools/check-agent-docs.ps1
```

Expected: PASS.

- [ ] **Step 3: Run focused regression suite**

Run:

```bash
./mvnw -Dtest=BondedCompanionPanelSnapshotCacheTest,\
BondedCompanionPanelLifecycleIntegrationTest,\
BondedCompanionPanelLiveProfileOverlayTest,\
BondedCompanionPanelCachedEntrySourceTest,\
BondedCompanionPanelFeaturePresentationSourceTest,\
BondedCompanionPanelRefreshSignalSourceTest,\
BondedCompanionCommandPageRoutingIntegrationTest,\
LinkedPanelRefreshCoordinatorTest,\
LinkedNpcPanelCardRenderStateTest,\
TameworkCommandSelectionPageNavigationTest,\
BondedCompanionCardPresenterTest test
```

Expected: PASS.

- [ ] **Step 4: Run thread-safety checks**

```bash
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
./mvnw -Dtest=EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Expected: no newly introduced unsafe match; both guard tests PASS.

- [ ] **Step 5: Run full suite**

Run: `./mvnw test`

Expected: PASS.

- [ ] **Step 6: Review the final diff and commit documentation/fixes**

Confirm `git status --short` still lists the user's Avatar Flight files as
unstaged and no generated/runtime copy was edited. Stage only task-owned files.

```bash
git add CHANGELOG.md docs/Command-Items.md \
  wiki/Player-Guides/Companion-Controls/Linked-Panel-Guide.md
git commit -m "Docs: describe responsive bonded roster panel"
```
