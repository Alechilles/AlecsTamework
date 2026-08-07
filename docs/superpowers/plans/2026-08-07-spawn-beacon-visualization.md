# Spawn Beacon Visualization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `/tw showspawnbeacons [radius|off]` so loaded natural spawn beacons are represented by exact-model, non-spawning visual proxies.

**Architecture:** A focused command manages player sessions while a dedicated visualization service scans loaded `LegacySpawnBeaconEntity` sources, computes union coverage, and owns presentation-only proxy references per world. A small pure coverage policy provides the behavioral test seam; engine ECS work remains world-thread-bound and is verified by compilation, Workshop reference validation, and optional live testing.

**Tech Stack:** Java 25, Hytale release/0.5.7 server API, Hytale ECS, JUnit 5, Gradle

## Global Constraints

- Proxies must never contain `SpawnBeacon`, `LegacySpawnBeaconEntity`, `LocalSpawnBeacon`, persistent visual components, or gameplay components.
- All entity discovery and mutation must execute on the owning world thread.
- The command defaults to 64 blocks and clamps explicit radii to 1-256 blocks.
- Multiple active viewers share one proxy per source beacon; one viewer ending must not remove coverage supplied by another.
- Only proxies created by this feature may be removed.
- Do not modify the natural beacon entity.

---

### Task 1: Coverage Policy and Regression Test

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/commands/SpawnBeaconVisualizationCoverage.java`
- Create: `src/test/java/com/alechilles/alecstamework/commands/SpawnBeaconVisualizationCoverageTest.java`

**Interfaces:**
- Consumes: JOML `Vector3d` positions.
- Produces: `static boolean isCovered(Vector3d sourcePosition, Collection<ViewerRange> viewers)` and `record ViewerRange(Vector3d position, double radius)`.

- [ ] **Step 1: Write the failing behavioral test**

```java
@Test
void sourceRemainsCoveredWhenAnyActiveViewerIncludesIt() {
    Vector3d source = new Vector3d(10.0, 0.0, 0.0);
    var viewers = List.of(
            new SpawnBeaconVisualizationCoverage.ViewerRange(new Vector3d(100.0, 0.0, 0.0), 20.0),
            new SpawnBeaconVisualizationCoverage.ViewerRange(new Vector3d(0.0, 0.0, 0.0), 16.0)
    );

    assertTrue(SpawnBeaconVisualizationCoverage.isCovered(source, viewers));
    assertFalse(SpawnBeaconVisualizationCoverage.isCovered(source, viewers.subList(0, 1)));
}
```

This catches the production regression where refresh logic considers only one viewer or removes a shared proxy when another viewer still covers its source.

- [ ] **Step 2: Run the targeted test and verify RED**

Run: `bash ../gradlew :alecstamework:test --tests '*SpawnBeaconVisualizationCoverageTest'`

Expected: compilation failure because `SpawnBeaconVisualizationCoverage` does not exist.

- [ ] **Step 3: Implement the minimal coverage policy**

```java
final class SpawnBeaconVisualizationCoverage {
    static boolean isCovered(Vector3d sourcePosition, Collection<ViewerRange> viewers) {
        for (ViewerRange viewer : viewers) {
            if (sourcePosition.distanceSquared(viewer.position()) <= viewer.radius() * viewer.radius()) {
                return true;
            }
        }
        return false;
    }

    record ViewerRange(Vector3d position, double radius) {
    }
}
```

- [ ] **Step 4: Run the targeted test and verify GREEN**

Run: `bash ../gradlew :alecstamework:test --tests '*SpawnBeaconVisualizationCoverageTest'`

Expected: PASS with zero failed tests.

- [ ] **Step 5: Commit the focused policy**

```bash
git add src/main/java/com/alechilles/alecstamework/commands/SpawnBeaconVisualizationCoverage.java src/test/java/com/alechilles/alecstamework/commands/SpawnBeaconVisualizationCoverageTest.java
git commit -m "Test: cover shared spawn beacon visibility"
```

### Task 2: Exact-Model Proxy Service and Command

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/commands/SpawnBeaconVisualizationService.java`
- Create: `src/main/java/com/alechilles/alecstamework/commands/TameworkShowSpawnBeaconsCommand.java`
- Modify: `src/main/java/com/alechilles/alecstamework/commands/TameworkCommandRoot.java`
- Modify: `src/test/java/com/alechilles/alecstamework/commands/TameworkCommandExecutionScopeTest.java`

**Interfaces:**
- Consumes: `TameworkShowSpawnMarkersCommandSupport.parse(String)`, active player/world/store context, and Hytale `LegacySpawnBeaconEntity` sources.
- Produces: `SpawnBeaconVisualizationService.enable(...)`, `disable(...)`, scheduled `refresh(...)`, and player command `/tw showspawnbeacons`.

- [ ] **Step 1: Add the command-scope regression assertion and verify RED**

Add `TameworkShowSpawnBeaconsCommand.class` to the `playerCommands` array in `TameworkCommandExecutionScopeTest`.

Run: `bash ../gradlew :alecstamework:test --tests '*TameworkCommandExecutionScopeTest'`

Expected: compilation failure because the command class does not exist.

- [ ] **Step 2: Implement the service boundary**

Create `SpawnBeaconVisualizationService` with:

```java
final class SpawnBeaconVisualizationService {
    EnableResult enable(World world, Store<EntityStore> store, PlayerRef playerRef, double radius);
    DisableResult disable(UUID playerUuid);

    record EnableResult(int visibleCount, int skippedCount, double radius) {}
    record DisableResult(boolean wasActive) {}
}
```

Internally keep concurrent player-session and per-world proxy maps. Refresh on the world thread every second, collect all valid viewer ranges for that world, collect source snapshots before mutating the store, retain one proxy per covered source UUID, and remove stale owned proxies with `RemoveReason.REMOVE`.

- [ ] **Step 3: Implement exact Hytale 0.5.7 visual selection**

For each new proxy, resolve `source.getSpawnWrapper().getSpawn().getModel()` through `ModelAsset`; use `Model.createUnitScaleModel(modelAsset)` when present and `SpawningPlugin.get().getSpawnMarkerModel()` otherwise. Build a non-persistent holder containing only UUID, transform, model, display name, nameplate, and `HiddenFromAdventurePlayers`, then add it with `AddReason.SPAWN`.

- [ ] **Step 4: Implement the command and register it**

Create `TameworkShowSpawnBeaconsCommand extends AbstractPlayerCommand`, parse `[radius|off]` with the existing marker parser, send bounded success/usage messages, and call the service. Register it beside `TameworkShowSpawnMarkersCommand` in `TameworkCommandRoot`.

- [ ] **Step 5: Run focused command tests and compile verification**

Run:

```bash
bash ../gradlew :alecstamework:test --tests '*SpawnBeaconVisualizationCoverageTest' --tests '*TameworkCommandExecutionScopeTest'
```

Expected: both test classes pass.

- [ ] **Step 6: Validate Hytale engine references**

Pass the full contents of both new engine-touching Java files to Hytale Workshop `validate_hytale_code_refs` against `release/0.5.7`. Correct every `not_found` reference and investigate relevant `unverifiable` results.

- [ ] **Step 7: Commit the command implementation**

```bash
git add src/main/java/com/alechilles/alecstamework/commands/SpawnBeaconVisualizationService.java src/main/java/com/alechilles/alecstamework/commands/TameworkShowSpawnBeaconsCommand.java src/main/java/com/alechilles/alecstamework/commands/TameworkCommandRoot.java src/test/java/com/alechilles/alecstamework/commands/TameworkCommandExecutionScopeTest.java
git commit -m "Feat: visualize natural spawn beacons"
```

### Task 3: Player Documentation and Full Verification

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/Actions-Sensors-Components.md`
- Modify: `docs/Debugging.md`

**Interfaces:**
- Consumes: final `/tw showspawnbeacons [radius|off]` behavior.
- Produces: player-facing command discovery and troubleshooting guidance.

- [ ] **Step 1: Document the shipped behavior**

Add an Unreleased `Added` entry explaining that nearby natural beacons can be visualized with their configured model/nameplate without changing spawn behavior. Add the command to the command reference and list it among player-scoped commands.

- [ ] **Step 2: Run documentation and whitespace checks**

Run:

```bash
git diff --check
bash scripts/tools/check-agent-docs.ps1
```

If the PowerShell script cannot run under Git Bash, invoke it through `powershell.exe -File scripts/tools/check-agent-docs.ps1` while retaining Git Bash as the controlling shell.

- [ ] **Step 3: Run full Java verification**

Run:

```bash
bash ../gradlew :alecstamework:test
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: Gradle exits 0; the grep introduces no unsafe new command/runtime access.

- [ ] **Step 4: Inspect the final diff and commit documentation**

```bash
git diff --check
git status --short
git diff --stat HEAD~2..HEAD
git add CHANGELOG.md docs/Actions-Sensors-Components.md docs/Debugging.md
git commit -m "Docs: document spawn beacon visualization"
```

- [ ] **Step 5: Record live-verification gap accurately**

If no local Hytale server is launched, report model/nameplate appearance and runtime cleanup as live-verification gaps. Do not launch `runAllMods` unless live-server verification is explicitly requested.
