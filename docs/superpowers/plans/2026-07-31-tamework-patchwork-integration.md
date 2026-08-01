# Tamework Patchwork Integration And Migration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Tamework's private asset patcher with an embedded Patchwork 1.0.0 provider while preserving patch behavior, legacy-root compatibility, Tamework macros, reload observations, and generated-config override behavior.

**Architecture:** Tamework shades `patchwork-runtime` and owns one small integration package. `TameworkPatchworkRuntime` bootstraps the embedded candidate and registers `TameworkPatchworkContribution`; three focused macro providers retain Tamework-only schema knowledge; a target adapter and observation bridge connect Patchwork transactions to Tamework's asset registrations. Patchwork owns generation and `/patchwork`; Tamework removes its patch engine and `/tw patches` surface.

**Tech Stack:** Java 25, Maven, Patchwork Runtime 1.0.0, Gson, JUnit 5, existing Hytale asset event APIs and Tamework reload services.

**Global Constraints:** Work in `C:\Users\22ale\AppData\Roaming\Hytale\Modding\alecstamework` using Git Bash. Begin only after `com.alechilles:patchwork-runtime:1.0.0` has been installed by the runtime plan. Preserve unrelated avatar-flight worktree changes. Do not duplicate Patchwork implementation classes in Tamework. Do not retain `/tw patches` aliases. Do not add an automatic generation trigger for asset or config events. Keep all game-state and asset operations on their currently authorized threads. Run `./mvnw.cmd test`, the agent-doc check, and jar inspection before completion.

---

## Dependency And Ownership Boundaries

Tamework may import only `com.alechilles.patchwork.embedded.*`. It must not import Patchwork's `coordinator`, `generation`, `discovery`, `engine`, `conditions`, `reload`, `command`, or `selftest` packages.

Create this integration package:

```text
src/main/java/com/alechilles/alecstamework/integration/patchwork/
  TameworkPatchworkRuntime.java
  TameworkPatchworkContribution.java
  TameworkInteractionBridgeMacro.java
  TameworkHookInstructionMacro.java
  TameworkStateInstructionMacro.java
  TameworkPatchTargetAdapter.java
  TameworkPatchObservationBridge.java
```

Stable contribution identifiers:

```text
hostPluginIdentifier = Alechilles:Alec's Tamework!
macroId = TameworkInteractionBridge
macroId = TameworkHookInstruction
macroId = TameworkStateInstruction
adapterId = Alechilles:Tamework:Assets
```

`TameworkPatchworkRuntime` is the only Tamework class allowed to own `EmbeddedPatchworkService` and `PatchworkContributionHandle`. Its lifecycle is:

```java
public final class TameworkPatchworkRuntime implements AutoCloseable {
    public TameworkPatchworkRuntime(JavaPlugin plugin,
                                    TameworkPatchTargetAdapter targetAdapter,
                                    TameworkPatchObservationBridge observationBridge);
    public void start();
    public Path generatedPatchRoot();
    public void recordAssetStoreMonitor(AssetStoreMonitorEvent event);
    public void recordCommonAssetMonitor(CommonAssetMonitorEvent event);
    public void recordLoadedAssets(Class<?> assetClass, AssetMap<?, ?> assetMap, Iterable<?> keys);
    @Override public void close();
}
```

## Task 1: Pin And Package The Embedded Runtime

**Files:**
- Modify: `pom.xml`
- Create: `src/test/java/com/alechilles/alecstamework/integration/patchwork/PatchworkDependencyBoundaryTest.java`
- Create: `src/test/java/com/alechilles/alecstamework/integration/patchwork/PatchworkPackagingIT.java`

- [ ] **Step 1: Add a failing dependency boundary test**

Scan Tamework source imports. Permit only `com.alechilles.patchwork.embedded.*`; fail on imports from Patchwork internal packages. Assert the old `com.alechilles.alecstamework.assets.patches` package is not used outside the not-yet-migrated files so the allowlist shrinks to zero by Task 5.

- [ ] **Step 2: Add a failing packaged-jar integration test**

Configure Maven Failsafe to run `PatchworkPackagingIT` during `verify`. Open Tamework's shaded jar and assert it contains `com/alechilles/patchwork/embedded/EmbeddedPatchworkBootstrap.class` exactly once and contains no standalone `PatchworkPlugin.class` or second Patchwork plugin manifest.

- [ ] **Step 3: Run and confirm failure**

```bash
./mvnw.cmd -Dtest=PatchworkDependencyBoundaryTest -Dit.test=PatchworkPackagingIT verify
```

Expected: FAIL because the dependency is absent and the old implementation remains.

- [ ] **Step 4: Add the pinned Maven dependency and shade include**

Add:

```xml
<dependency>
  <groupId>com.alechilles</groupId>
  <artifactId>patchwork-runtime</artifactId>
  <version>1.0.0</version>
</dependency>
```

Add `com.alechilles:patchwork-runtime` to the existing shade artifact set beside telemetry. Do not relocate Patchwork packages and do not add a standalone dependency.

- [ ] **Step 5: Run the focused tests and commit**

Temporarily scope the source boundary assertion to imports outside `assets/patches` and old patch command files; Task 5 removes the exception.

```bash
./mvnw.cmd -Dtest=PatchworkDependencyBoundaryTest -Dit.test=PatchworkPackagingIT verify
git add pom.xml src/test/java/com/alechilles/alecstamework/integration/patchwork
git commit -m 'Build: embed Patchwork runtime in Tamework'
```

## Task 2: Add The Embedded Lifecycle Wrapper

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchworkRuntime.java`
- Create: `src/test/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchworkRuntimeTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`

- [ ] **Step 1: Add failing lifecycle tests**

With a fake `EmbeddedPatchworkService`, assert `start()` bootstraps exactly once, starts before contribution registration, exposes the active winner's generated root, closes the contribution before the service, tolerates close before start, and does not keep a stale handle after failed startup.

- [ ] **Step 2: Run and confirm failure**

```bash
./mvnw.cmd -Dtest=TameworkPatchworkRuntimeTest test
```

- [ ] **Step 3: Implement the wrapper with injectable seams**

Production construction calls `EmbeddedPatchworkBootstrap.bootstrap(plugin)`. A package-private constructor accepts a bootstrap function for tests. Keep lifecycle synchronization in this class; do not add Patchwork state to `Tamework` beyond one field.

- [ ] **Step 4: Wire setup and shutdown in `Tamework`**

Replace `AssetPatchSelfTestPack` and `AssetPatchService` fields with:

```java
private TameworkPatchworkRuntime patchworkRuntime;
```

Construct it during `setupInternal()` after the reload services it needs exist, call `start()` where `registerLoadHook()` currently runs, and call `close()` during shutdown before those reload services are discarded. If Patchwork startup fails, log one actionable error and allow unrelated Tamework features to start.

- [ ] **Step 5: Run tests and commit**

```bash
./mvnw.cmd -Dtest=TameworkPatchworkRuntimeTest test
git add src/main/java/com/alechilles/alecstamework/Tamework.java src/main/java/com/alechilles/alecstamework/integration/patchwork src/test/java/com/alechilles/alecstamework/integration/patchwork
git commit -m 'Refactor: bootstrap embedded Patchwork from Tamework'
```

## Task 3: Preserve Tamework Macros As Host Contributions

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchworkContribution.java`
- Create: `src/main/java/com/alechilles/alecstamework/integration/patchwork/TameworkInteractionBridgeMacro.java`
- Create: `src/main/java/com/alechilles/alecstamework/integration/patchwork/TameworkHookInstructionMacro.java`
- Create: `src/main/java/com/alechilles/alecstamework/integration/patchwork/TameworkStateInstructionMacro.java`
- Create: `src/test/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchworkContributionTest.java`
- Create: `src/test/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchMacroTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchworkRuntime.java`

- [ ] **Step 1: Port macro behavior into failing tests**

Copy the current `AssetPatchMacroExpander` expectations exactly:

- `TameworkInteractionBridge` emits prompt and interaction branches with the existing sensors, locks, action fields, IDs, positions, and matchers.
- `TameworkHookInstruction` emits the `TameworkHook` sensor, required `HookId`, default `Consume=true`, optional instructions, and stable matcher.
- `TameworkStateInstruction` emits the selected component instruction plus optional enabled/sensor objects and stable matcher.

Also assert each provider rejects malformed options with the current actionable messages and does not mutate the input JSON.

- [ ] **Step 2: Run and confirm failure**

```bash
./mvnw.cmd -Dtest=TameworkPatchworkContributionTest,TameworkPatchMacroTest test
```

- [ ] **Step 3: Implement one focused provider per macro**

Use the exact case-preserving IDs above. `TameworkPatchworkContribution` returns immutable lists and reads its version from Tamework's manifest. It must have no dependency on Patchwork internals.

- [ ] **Step 4: Register the contribution through the wrapper**

Register once after the embedded service starts. Store the returned handle and close it during wrapper shutdown. If a newer Patchwork winner appears, rely on Patchwork forwarding; do not re-bootstrap or re-register from Tamework.

- [ ] **Step 5: Run tests and commit**

```bash
./mvnw.cmd -Dtest=TameworkPatchworkContributionTest,TameworkPatchMacroTest,TameworkPatchworkRuntimeTest test
git add src/main/java/com/alechilles/alecstamework/integration/patchwork src/test/java/com/alechilles/alecstamework/integration/patchwork
git commit -m 'Feat: contribute Tamework patch macros to Patchwork'
```

## Task 4: Bridge Tamework Reloads And Observations

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchTargetAdapter.java`
- Create: `src/main/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchObservationBridge.java`
- Create: `src/test/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchTargetAdapterTest.java`
- Create: `src/test/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchObservationBridgeTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchworkContribution.java`
- Modify: `src/main/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchworkRuntime.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`

- [ ] **Step 1: Add adapter classification tests**

Port the effective expectations from `AssetPatchTargetClassifierTest` and `AssetPatchReloadCoordinatorTest`. Cover Tamework custom configs, Items, ParticleSystems, common assets, targets whose existing Tamework reload service can reload safely, and targets that must be reported `restartRequired`. Assert no fallback calls a generic asset reload.

- [ ] **Step 2: Add observation correlation tests**

Cover `LoadedAssetsEvent`, `RemovedAssetsEvent`, `AssetStoreMonitorEvent`, and `CommonAssetMonitorEvent`. Only emit observations for `Alechilles:Patchwork_GeneratedPatches`, normalize target paths, include the current epoch and expected hash supplied by Patchwork, and ignore initial load events or unrelated packs. A late event from an older epoch must not confirm the active transaction.

When `reload(PatchworkReloadRequest)` begins, store its immutable `PatchworkTargetExpectation` entries in the observation bridge. Loaded and removed events consume only matching pending expectations; no event is allowed to invent an expected hash.

- [ ] **Step 3: Run and confirm failure**

```bash
./mvnw.cmd -Dtest=TameworkPatchTargetAdapterTest,TameworkPatchObservationBridgeTest test
```

- [ ] **Step 4: Implement the adapter using existing focused services**

Delegate only to existing Tamework reload services such as item-feature, companion, command-item, population-group, and other explicitly supported config families. Return `PatchworkReloadResult` containing exact reloaded, restart-required, and failed targets. Schedule on the same world/main-thread mechanisms those services already use.

- [ ] **Step 5: Replace event forwarding in `Tamework`**

Replace `recordAssetPatchHotReload`, `onAssetStoreMonitor`, and `onCommonAssetMonitor` calls to `assetPatchService` with methods on `patchworkRuntime`. Keep existing non-Patchwork cache clears and reconciliation calls intact. Removed-asset events used by a target adapter must forward removal outcomes as well.

- [ ] **Step 6: Run tests and commit**

```bash
./mvnw.cmd -Dtest=TameworkPatchTargetAdapterTest,TameworkPatchObservationBridgeTest,TameworkPatchworkRuntimeTest test
git add src/main/java/com/alechilles/alecstamework/Tamework.java src/main/java/com/alechilles/alecstamework/integration/patchwork src/test/java/com/alechilles/alecstamework/integration/patchwork
git commit -m 'Feat: bridge Tamework asset reloads to Patchwork'
```

## Task 5: Migrate Generated-Root Consumers And Remove The Old Patcher

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/config/overrides/TwConfigOverrideManager.java`
- Modify: `src/test/java/com/alechilles/alecstamework/config/overrides/TwConfigOverrideManagerPathTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/commands/TameworkCommandRoot.java`
- Delete: `src/main/java/com/alechilles/alecstamework/commands/TameworkPatchesCommand.java`
- Delete: `src/main/java/com/alechilles/alecstamework/commands/TameworkPatchesReloadCommand.java`
- Delete: `src/main/java/com/alechilles/alecstamework/commands/TameworkPatchesSelfTestCommand.java`
- Delete: `src/main/java/com/alechilles/alecstamework/commands/TameworkPatchesStatusCommand.java`
- Delete: every file under `src/main/java/com/alechilles/alecstamework/assets/patches/`
- Delete: tests under `src/test/java/com/alechilles/alecstamework/assets/patches/` after their behavior is represented in Patchwork or integration tests
- Modify: `src/test/java/com/alechilles/alecstamework/integration/patchwork/PatchworkDependencyBoundaryTest.java`
- Create: `src/test/java/com/alechilles/alecstamework/integration/patchwork/RemovedAssetPatcherArchitectureTest.java`

- [ ] **Step 1: Strengthen config-override path tests before changing production code**

Keep these exact guarantees:

- a generated `Server/Tamework/Config/...json` file is eligible for override resolution;
- a generated `Server/Tamework/Patches/...json` definition is never treated as a config override;
- a path escaping the generated root is rejected;
- no Patchwork runtime returns `null` as the generated-root contract.

- [ ] **Step 2: Run the focused path test**

```bash
./mvnw.cmd -Dtest=TwConfigOverrideManagerPathTest test
```

- [ ] **Step 3: Change generated-root lookup**

Change `TwConfigOverrideManager` to accept a `Supplier<Path>` generated-root dependency and construct it with `patchworkRuntime::generatedPatchRoot`. Replace `plugin.getAssetPatchService().getGeneratedPatchCacheRoot()` with that supplier. Preserve `resolveGeneratedPatchPath` containment and patch-definition exclusion; do not add a public Patchwork accessor to `Tamework`.

- [ ] **Step 4: Remove `/tw patches` and old accessors**

Remove the subcommand registration from `TameworkCommandRoot`, delete the four command classes, delete `getAssetPatchService()` and `getAssetPatchSelfTestPack()`, and remove their fields/imports. Do not add aliases; administrators use `/patchwork status|reload|selftest`.

- [ ] **Step 5: Delete the old implementation and tighten architecture checks**

Delete the full old patch package and its now-duplicated tests. `RemovedAssetPatcherArchitectureTest` must assert:

```java
assertFalse(Files.exists(mainJava.resolve("com/alechilles/alecstamework/assets/patches")));
assertFalse(sourceText.contains("TameworkPatchesCommand"));
assertFalse(sourceText.contains("getAssetPatchService"));
assertFalse(sourceText.contains("TameworkSetting"));
```

Remove every temporary boundary-test exception added in Task 1.

- [ ] **Step 6: Run focused and full tests, then commit**

```bash
./mvnw.cmd -Dtest=TwConfigOverrideManagerPathTest,PatchworkDependencyBoundaryTest,RemovedAssetPatcherArchitectureTest test
./mvnw.cmd test
git add -A src/main/java/com/alechilles/alecstamework/assets src/main/java/com/alechilles/alecstamework/commands src/main/java/com/alechilles/alecstamework/config/overrides src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework
git commit -m 'Refactor: remove Tamework asset patcher implementation'
```

## Task 6: Move Tamework's Bundled Definitions To The Neutral Root

**Files:**
- Move: `src/main/resources/Server/Tamework/Patches/BlockSets/Tamework_Fence_Feed_Trough_Patch.json` to `src/main/resources/Server/Patchwork/Patches/BlockSets/Tamework_Fence_Feed_Trough_Patch.json`
- Move: `src/main/resources/Server/Tamework/Patches/Examples/Tamework_Example_Patch.json` to `src/main/resources/Server/Patchwork/Patches/Examples/Tamework_Example_Patch.json`
- Move: `src/main/resources/Server/Tamework/Patches/Items/Tamework_Container_Bucket_Water_Trough_Patch.json` to `src/main/resources/Server/Patchwork/Patches/Items/Tamework_Container_Bucket_Water_Trough_Patch.json`
- Move: `src/main/resources/Server/Tamework/Patches/Items/Tamework_Deco_Bucket_Water_Trough_Patch.json` to `src/main/resources/Server/Patchwork/Patches/Items/Tamework_Deco_Bucket_Water_Trough_Patch.json`
- Move: `src/main/resources/Server/Tamework/Patches/Items/Tamework_Tool_Capture_Crate_Patch.json` to `src/main/resources/Server/Patchwork/Patches/Items/Tamework_Tool_Capture_Crate_Patch.json`
- Modify: resource-path tests that name `Server/Tamework/Patches`
- Create: `src/test/java/com/alechilles/alecstamework/integration/patchwork/TameworkPatchResourceLayoutTest.java`

- [ ] **Step 1: Add a failing resource layout test**

Assert all five definitions exist under `Server/Patchwork/Patches`, no bundled file remains under `Server/Tamework/Patches`, every JSON file parses, IDs are unique within the neutral root, and all Tamework macro IDs have a registered contribution provider.

- [ ] **Step 2: Run and confirm failure**

```bash
./mvnw.cmd -Dtest=TameworkPatchResourceLayoutTest test
```

- [ ] **Step 3: Move files without changing patch semantics**

Use `git mv`. Do not rewrite IDs, priorities, targets, operations, or macro options during the move. The example may be updated only to replace root-specific prose.

- [ ] **Step 4: Run tests and commit**

```bash
./mvnw.cmd -Dtest=TameworkPatchResourceLayoutTest,ManagedCoopCaptureCrateAssetWiringTest test
git add -A src/main/resources src/test/java
git commit -m 'Refactor: move Tamework patches to Patchwork root'
```

## Task 7: Synchronize Tamework Documentation And Changelog

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/Architecture.md`
- Modify: `docs/Debugging.md`
- Modify: `docs/agents/agent-map.md`
- Modify: `docs/agents/runtime-vs-source-checklist.md`
- Modify: external wiki asset-patch pages and index under `C:\Users\22ale\AppData\Roaming\Hytale\My Mod Docs`
- Modify: external wiki Hooks/Bridges and Setup/Quick Start pages if they mention the old commands or root

- [ ] **Step 1: Update public behavior documentation**

State that Tamework includes Patchwork 1.0.0, standalone Patchwork is optional, election follows Patchwork's version-first hierarchy, both neutral and legacy roots remain readable when Tamework is installed, new Tamework definitions should use the neutral root, and `/tw patches` has been replaced by `/patchwork`.

- [ ] **Step 2: Document the condition migration**

Remove `TameworkSetting` documentation. Link to Patchwork's `Target`, `Asset`, and `ModData` JSON source forms. State that generation happens at startup or authorized `/patchwork reload`, not whenever a referenced config changes.

- [ ] **Step 3: Update architecture and debugging guidance**

Document the single wrapper/contribution boundary, active-runtime status fields, generated pack ID/root, quarantine and rollback-failed states, and how to detect a newer standalone winner. Update runtime/source checks to inspect both the Tamework jar's embedded runtime and an installed standalone jar.

- [ ] **Step 4: Add the player/modder-facing changelog entry**

Describe only the final unreleased behavior: Patchwork extraction, optional standalone override, new command root, both input roots, and generalized JSON conditions. Do not narrate intermediate design iterations.

- [ ] **Step 5: Regenerate and validate agent docs**

```bash
pwsh -NoProfile -File ./scripts/tools/build-agent-index.ps1
pwsh -NoProfile -File ./scripts/tools/check-agent-docs.ps1
```

- [ ] **Step 6: Commit documentation**

```bash
git add README.md CHANGELOG.md docs
git commit -m 'Docs: document Tamework Patchwork integration'
```

Record external wiki paths changed in the commit body or handoff because they are outside this repository.

## Task 8: Run Compatibility, Packaging, And Live Smoke Verification

**Files:**
- Modify only if a verification failure proves a scoped defect in the preceding tasks.

- [ ] **Step 1: Run source and architecture checks**

```bash
grep -R -n -E 'AssetPatchService|AssetPatchSelfTestPack|TameworkPatches(Command|Reload|Status|SelfTest)|TameworkSetting|getAssetPatchService' src/main src/test || true
grep -R -n 'Server/Tamework/Patches' src/main/resources || true
grep -R -n 'com\.alechilles\.patchwork\.' src/main/java | grep -v 'com.alechilles.patchwork.embedded' || true
```

Expected: first two commands print nothing; the third prints nothing outside imports/comments approved by the dependency-boundary test.

- [ ] **Step 2: Run the complete Tamework suite**

```bash
./mvnw.cmd clean test
```

- [ ] **Step 3: Inspect the packaged Tamework jar**

```bash
./mvnw.cmd clean package
jar tf target/*.jar | grep 'com/alechilles/patchwork/embedded/EmbeddedPatchworkBootstrap.class'
jar tf target/*.jar | grep 'com/alechilles/alecstamework/assets/patches' || true
jar tf target/*.jar | grep 'Server/Patchwork/Patches'
```

Expected: embedded bootstrap is present; old patcher classes are absent; all five neutral-root resources are present.

- [ ] **Step 4: Run the four-provider compatibility matrix on a disposable server**

Verify and capture `/patchwork status` for:

1. Tamework embedded 1.0.0 only: embedded wins and both roots scan.
2. Tamework embedded 1.0.0 plus standalone 1.0.0: standalone wins the equal-version tie.
3. Tamework embedded 1.0.0 plus newer compatible standalone: standalone wins by version.
4. Tamework embedded 1.0.0 plus older standalone: embedded wins by version.

For at least one case, run `/patchwork reload`, verify a Tamework custom target reloads through the adapter, and deliberately use a restart-required target to confirm Patchwork reports it without a generic reload. Run one failing live target to verify rollback or explicit rollback-failed diagnostics.

- [ ] **Step 5: Verify third-party compatibility fixtures**

Load a fixture equivalent to Cats No Defend using legacy target-relative `JsonPathEquals`; verify it still applies. Load one legacy-root definition and one duplicate neutral-root definition with the same effective key; verify neutral wins once. Load an `Animal Husbandry` or `Cats` fixture without `TameworkSetting`; verify no retired-condition dependency remains.

- [ ] **Step 6: Run final workspace checks and commit any verification-only tests**

```bash
pwsh -NoProfile -File ./scripts/tools/check-agent-docs.ps1
git status --short
```

If verification added only scoped regression tests, commit them:

```bash
git add src/test docs
git commit -m 'Test: verify Patchwork compatibility matrix'
```

Do not stage the pre-existing avatar-flight files. The final handoff must list Patchwork and Tamework commit IDs, full test commands, packaged jar paths, and the live matrix results.
