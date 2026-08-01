# Patchwork Runtime And Standalone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Patchwork 1.0.0 as a standalone Hytale plugin and a plain embeddable runtime jar, with one process-wide elected runtime and compatibility for both neutral and legacy Tamework patch roots.

**Architecture:** Create a two-module Maven repository. `patchwork-runtime` owns discovery, parsing, conditions, generation, publication, reload transactions, commands, self-tests, and the classloader-neutral election protocol. `patchwork-standalone` is only the Hytale plugin wrapper and shaded distribution. Every provider registers with one JVM-global registry; the highest compatible semantic version wins, and standalone wins only an equal-version tie. Host-specific behavior enters through narrow contribution interfaces instead of dependencies on Tamework.

**Tech Stack:** Java 25, Maven Wrapper, Gson, JUnit 5.10.2, Hytale server API, Maven Shade Plugin 3.6.0.

**Global Constraints:** Work in `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork`. Use Git Bash. Preserve the approved design in `alecstamework/docs/superpowers/specs/2026-07-31-patchwork-extraction-design.md`. Keep cross-classloader coordinator arguments to JDK types only. Never follow symlinks for condition sources. Never invoke a generic Hytale asset reload. Only the elected runtime may scan, publish, watch, register commands, or run self-tests. Commit after every task and do not include unrelated Tamework worktree changes.

---

## Public Contracts Fixed By This Plan

Use these identifiers consistently:

```text
Plugin ID: Alechilles:Patchwork
Java package: com.alechilles.patchwork
Maven runtime: com.alechilles:patchwork-runtime:1.0.0
Maven standalone: com.alechilles:patchwork-standalone:1.0.0
Neutral input root: Server/Patchwork/Patches
Legacy input root: Server/Tamework/Patches
Generated pack ID: Alechilles:Patchwork_GeneratedPatches
Shared data root: <server-or-save-root>/mods/Alechilles_Patchwork
Command root: /patchwork
Administrative permission: patchwork.admin
Registry property: com.alechilles.patchwork.coordinator.registry
Coordinator ABI: 1
```

The embeddable API is:

```java
package com.alechilles.patchwork.embedded;

public final class EmbeddedPatchworkBootstrap {
    public static EmbeddedPatchworkService bootstrap(JavaPlugin plugin);
}

public interface EmbeddedPatchworkService extends AutoCloseable {
    void start();
    PatchworkContributionHandle registerContribution(PatchworkHostContribution contribution);
    Path generatedPatchRoot();
    void recordObservation(PatchworkReloadObservation observation);
    @Override void close();
}

public interface PatchworkContributionHandle extends AutoCloseable {
    @Override void close();
}
```

Host extension contracts are:

```java
public interface PatchworkHostContribution {
    String hostPluginIdentifier();
    String contributionVersion();
    List<PatchworkMacroProvider> macroProviders();
    List<PatchworkTargetAdapter> targetAdapters();
}

public interface PatchworkMacroProvider {
    String macroId();
    JsonArray expand(JsonObject operation);
}

public interface PatchworkTargetAdapter {
    String adapterId();
    boolean supports(String target);
    CompletionStage<PatchworkReloadResult> reload(PatchworkReloadRequest request);
}

public record PatchworkTargetExpectation(String target, String expectedHash, boolean removal) {}
public record PatchworkReloadRequest(long epoch, List<PatchworkTargetExpectation> targets) {}
public record PatchworkReloadResult(
        String adapterId,
        List<String> reloadedTargets,
        List<String> restartRequiredTargets,
        List<String> failures) {}
public record PatchworkReloadObservation(
        long epoch,
        String adapterId,
        String target,
        String expectedHash,
        PatchworkObservationOutcome outcome) {}
public enum PatchworkObservationOutcome { LOADED, REMOVED, FAILED }
```

Internally, the reflective coordinator bridge serializes contributions and events into immutable `Map`, `List`, `String`, primitive wrapper, `Path`, `byte[]`, and `CompletionStage` values so these API classes never cross provider classloaders.

## Repository Layout

```text
Patchwork/
  AGENTS.md
  LICENSE.txt
  README.md
  CHANGELOG.md
  pom.xml
  mvnw
  mvnw.cmd
  .mvn/wrapper/
  runtime/
    pom.xml
    src/main/java/com/alechilles/patchwork/
      embedded/
      coordinator/
      conditions/
      discovery/
      engine/
      generation/
      reload/
      command/
      selftest/
    src/test/java/com/alechilles/patchwork/
  standalone/
    pom.xml
    src/main/java/com/alechilles/patchwork/standalone/PatchworkPlugin.java
    src/main/resources/manifest.json
    src/test/java/com/alechilles/patchwork/standalone/StandalonePackagingIT.java
```

## Task 1: Scaffold The Reactor And Artifact Boundaries

**Files:**
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork\pom.xml`
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork\runtime\pom.xml`
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork\standalone\pom.xml`
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork\AGENTS.md`
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork\LICENSE.txt`
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork\README.md`
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork\CHANGELOG.md`
- Copy: Tamework's `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/`
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork\standalone\src\main\resources\manifest.json`
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\Patchwork\standalone\src\test\java\com\alechilles\patchwork\standalone\StandalonePackagingIT.java`

- [ ] **Step 1: Initialize the repository and write a failing packaging integration test**

Configure Maven Failsafe to run `StandalonePackagingIT` during `verify`. Initially, the test must open the built jars and assert that the runtime has no Hytale `manifest.json`, the standalone manifest declares `Alechilles:Patchwork`, and only the standalone POM configures shading. Task 10 extends this test to assert the final embedded bootstrap and plugin classes.

- [ ] **Step 2: Run the test and confirm the scaffold is incomplete**

```bash
cd '/c/Users/22ale/AppData/Roaming/Hytale/Modding/Patchwork'
./mvnw.cmd -pl standalone -am verify
```

Expected: FAIL because the modules or production classes do not exist.

- [ ] **Step 3: Create the parent and module POMs**

Use parent coordinates `com.alechilles:patchwork-parent:1.0.0`, modules `runtime` and `standalone`, Java release 25, Gson, JUnit 5.10.2, and the same Hytale system dependency path used by Tamework. Configure shade only in `standalone`; exclude Maven signatures and emit `patchwork-standalone-1.0.0.jar`.

- [ ] **Step 4: Add identity files and a minimal plugin manifest**

Adapt Tamework's source-available license because the implementation is extracted from Tamework. State both Maven coordinates and both installation modes in the README. Do not claim the runtime is functional yet.

- [ ] **Step 5: Run the reactor and commit**

```bash
./mvnw.cmd test
git add .
git commit -m 'Build: scaffold Patchwork modules'
```

## Task 2: Port The Patch Model And Pure Engine

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchDefinition.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchOperation.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchEngine.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/engine/PatchMacroRegistry.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchDefinitionTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchEngineTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/engine/PatchMacroRegistryTest.java`

- [ ] **Step 1: Port engine regression tests under the new package**

Cover `Add`, `Remove`, `Replace`, `Merge`, `Insert`, multi-target parsing, ascending priority then patch ID then source-pack load order, disabled patches, invalid JSON Pointer paths, and deterministic error messages. Add a failing test showing an unknown macro ID is rejected without a Tamework dependency.

- [ ] **Step 2: Verify the ported tests fail**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchDefinitionTest,PatchEngineTest,PatchMacroRegistryTest test
```

- [ ] **Step 3: Port and rename the pure model and engine**

Preserve the existing JSON schema and operation semantics. Replace `AssetPatchMacroExpander`'s hard-coded Tamework cases with `PatchMacroRegistry`, keyed by exact macro ID. Reject duplicate macro IDs and include the contributing host ID in the error.

- [ ] **Step 4: Run tests and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchDefinitionTest,PatchEngineTest,PatchMacroRegistryTest test
git add runtime
git commit -m 'Feat: add Patchwork patch engine'
```

## Task 3: Implement Discovery, Root Precedence, And Duplicate Rules

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchRoot.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchSource.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchScanner.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/discovery/PatchTargetResolver.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/discovery/PatchScannerTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/discovery/PatchTargetResolverTest.java`

- [ ] **Step 1: Add failing discovery tests**

Cover directory and archive packs, neutral root enabled for every installation, legacy root enabled only when `Alechilles:Alec's Tamework!` is installed, normalized path traversal rejection, stable scan order, and target resolution against registered asset packs.

- [ ] **Step 2: Add the duplicate-key contract test**

The effective key is `(sourcePackId, patchId, expandedTarget)`. Assert that a neutral definition replaces the matching legacy definition, a second matching definition inside one root fails that source file, and unrelated pack IDs never collide.

- [ ] **Step 3: Run and confirm failure**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchScannerTest,PatchTargetResolverTest test
```

- [ ] **Step 4: Implement immutable scan results and precedence**

Return definitions, skipped entries, and failures as one immutable result. Sort packs, files, and expanded targets before deduplication so diagnostics are repeatable. Ignore the generated Patchwork pack as an input.

- [ ] **Step 5: Run tests and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchScannerTest,PatchTargetResolverTest test
git add runtime
git commit -m 'Feat: discover neutral and legacy patch roots'
```

## Task 4: Generalize JSON Conditions And Secure Mod Data Reads

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/conditions/PatchCondition.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/conditions/PatchConditionParser.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/conditions/PatchConditionEvaluator.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/conditions/ConditionSource.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/conditions/ConditionSourceResolver.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/conditions/ModDataRootRegistry.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/conditions/ConditionDocumentCache.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/conditions/PatchConditionParserTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/conditions/PatchConditionEvaluatorTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/conditions/ModDataRootRegistryTest.java`

- [ ] **Step 1: Add parser compatibility tests**

Preserve `All`, `Any`, `Not`, `ModInstalled`, `AssetExists`, `AssetMissing`, `TargetExists`, `ModVersion`, `GameVersion`, `ServerVersion`, existing target-relative `JsonPathExists`, and existing target-relative `JsonPathEquals`. Add the explicit source forms:

```json
{ "JsonPathEquals": { "Source": { "Type": "Target" }, "Path": "/Enabled", "Equals": true } }
{ "JsonPathExists": { "Source": { "Type": "Asset", "Path": "Server/Config/Other.json" }, "Path": "/Feature" } }
{ "JsonPathEquals": { "Source": { "Type": "ModData", "Mod": "Example:Mod", "Path": "config/settings.json" }, "Path": "/my/example/field", "Equals": true } }
```

Omitted `Source` remains equivalent to `{ "Type": "Target" }`; preserve the existing top-level `Asset` shortcut. Reject the retired `TameworkSetting` key with a clear migration error. JSON equality is structural and type-sensitive.

- [ ] **Step 2: Add filesystem boundary tests**

Using temporary directories and a narrow deterministic read seam, assert exact mod-ID lookup, missing/unloaded plugin handling, relative normalized paths only, rejection of absolute paths and `..`, rejection of every symlink, junction, or reparse-like component, final resolved path containment, observable path/attribute swap failure, regular-file requirement, 4 MiB maximum before and during reads, UTF-8 JSON parsing, successful Windows-style null-file-key fallback, and no logged source or expected values.

- [ ] **Step 3: Run and confirm failure**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchConditionParserTest,PatchConditionEvaluatorTest,ModDataRootRegistryTest test
```

- [ ] **Step 4: Implement source resolution**

Resolve `ModData` roots from `PluginManager#getPlugins()` and `PluginBase#getDataDirectory()`. Use `SecureDirectoryStream` relative opens where supported. Otherwise reject symbolic/reparse-like components, open the final file read-only with `NOFOLLOW_LINKS`, and compare no-follow basic/DOS attributes, available file keys, real-path containment, size, and timestamps before and after the read. Fail closed on every observable change; permit a null file key on Windows while documenting that this practical fallback cannot eliminate a precisely timed unobservable parent-directory swap. Cache the first parsed document snapshot by source identity for one generation pass; do not start file watchers.

- [ ] **Step 5: Implement safe diagnostics**

Diagnostics may contain mod ID, relative path, JSON path, and reason, but never the resolved JSON value. Treat a missing source as condition-not-met with a warning; treat malformed condition syntax as a patch failure.

- [ ] **Step 6: Run tests and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchConditionParserTest,PatchConditionEvaluatorTest,ModDataRootRegistryTest test
git add runtime
git commit -m 'Feat: add generalized JSON patch conditions'
```

## Task 5: Build The Generation Pipeline And Safe Startup Publisher

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/generation/PatchGenerationService.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/generation/GeneratedPackLayout.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/generation/GeneratedPackManifest.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/generation/StartupPackPublisher.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/generation/PatchworkEarlyLoadHook.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/generation/PatchStatusSnapshot.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/generation/PatchGenerationServiceTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/generation/StartupPackPublisherTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/generation/PatchworkEarlyLoadHookTest.java`

- [ ] **Step 1: Add failing orchestration tests**

Assert scan/resolve/condition/apply order, one immutable status snapshot, deterministic manifest entries, complete rejection of a failed target, and continued staging of unrelated successful targets. Assert the active owner alone handles the early `LoadAssetEvent` at the current Tamework patcher's priority, before broad server JSON validation; a passive callback must immediately no-op on a revoked epoch.

- [ ] **Step 2: Add startup recovery tests**

Assert generation into a fresh sibling staging directory, manifest written before activation, prior generated root moved to a unique diagnostics quarantine directory, atomic directory move when supported, recovery evidence retained on activation failure without registering it as current, and no reuse of stale prior generated files. Interrupted staging directories must be recognized and removed only after their exact generated-root parent is validated.

- [ ] **Step 3: Run and confirm failure**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchGenerationServiceTest,StartupPackPublisherTest,PatchworkEarlyLoadHookTest test
```

- [ ] **Step 4: Implement the startup pipeline**

The only automatic generation trigger is elected-runtime startup. Make `PatchGenerationService.generate(GenerationRequest)` pure until publication. `GeneratedPackLayout` owns only `<server-or-save-root>/mods/Alechilles_Patchwork/GeneratedPatches`, `SelfTest`, and `Diagnostics`; tests must reject any resolved path outside that root. Write complete target bytes and manifest to staging, fsync files where supported, then publish to `GeneratedPatches`. Register `Alechilles:Patchwork_GeneratedPatches` only after a successful publish.

- [ ] **Step 5: Run tests and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchGenerationServiceTest,StartupPackPublisherTest,PatchworkEarlyLoadHookTest test
git add runtime
git commit -m 'Feat: publish generated patches safely at startup'
```

## Task 6: Implement Transactional Live Reload

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/reload/PatchReloadCoordinator.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/reload/TargetPatchTransaction.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/reload/TargetJournalEntry.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/reload/PatchReloadTracker.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/reload/PatchTargetClassifier.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/reload/HytalePatchTargetAdapter.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/reload/GeneratedPackObserver.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/reload/PatchReloadCoordinatorTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/reload/PatchReloadTrackerTest.java`

- [ ] **Step 1: Add target-transaction tests**

Cover manifest staging/commit before target writes, per-target journal of old bytes and hash, atomic replace/delete, built-in versus host adapter selection, asset-store/particle/common/NPC observation, positive confirmation, timeout, observer failure, rollback confirmation, rollback-failed state, stale epoch rejection, and preservation of earlier successful targets when a later target fails.

- [ ] **Step 2: Add trigger tests**

Assert live generation occurs only from `/patchwork reload` authorized by `patchwork.admin`; source edits and generated-pack observations never initiate a new pass. Observations may only confirm or reject outputs from the current epoch.

- [ ] **Step 3: Run and confirm failure**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchReloadCoordinatorTest,PatchReloadTrackerTest test
```

- [ ] **Step 4: Implement transaction boundaries**

Serialize reload passes. Commit the new manifest before target transactions. For each target, journal, write, invoke its registered adapter, await matching `(epoch,target,expectedHash)`, then commit or rollback that target. Mark restart-required targets without attempting a generic asset reload.

- [ ] **Step 5: Run tests and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchReloadCoordinatorTest,PatchReloadTrackerTest test
git add runtime
git commit -m 'Feat: add transactional Patchwork reloads'
```

## Task 7: Implement The Process-Wide Election Registry

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/coordinator/PatchworkRuntimeOrigin.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/coordinator/PatchworkRuntimeCandidate.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/coordinator/PatchworkRegistrationToken.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/coordinator/PatchworkCoordinatorBridge.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/coordinator/PatchworkCoordinatorRegistry.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/coordinator/PatchworkRuntimeCandidateTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/coordinator/PatchworkCoordinatorRegistryTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/coordinator/ForeignClassLoaderElectionIT.java`

- [ ] **Step 1: Add candidate ordering tests**

Assert highest compatible semantic version first, release above suffixed prerelease, incompatible coordinator ABI ignored, standalone above embedded only on equal runtime version, and stable provider plugin ID/source path as the final tie-breaker.

Use this complete immutable descriptor shape: provider ID, `STANDALONE` or `EMBEDDED` origin, runtime version, coordinator ABI, provider plugin identifier/version, normalized source jar path, canonical shared data root, and reflective provider bridge. Assert every candidate resolves the same host-independent `Alechilles_Patchwork` root.

- [ ] **Step 2: Add lifecycle and fencing tests**

Assert one global registry property across all ABI versions, serialized registration/election, unique registration tokens, monotonically increasing ownership epochs, stale unregister ignored, old winner drained before new winner starts, startup failure restoring the old winner, one publication lease, and stale owners unable to publish.

- [ ] **Step 3: Add a true foreign-classloader test**

Configure this case as `ForeignClassLoaderElectionIT` under Maven Failsafe. Load two copied runtime jars in isolated `URLClassLoader`s, register through reflection, and assert one winner. The reflective surface may accept or return only `String`, primitive wrappers, `Map`, `List`, `Path`, `byte[]`, and `CompletionStage`.

- [ ] **Step 4: Run and confirm failure**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchworkRuntimeCandidateTest,PatchworkCoordinatorRegistryTest test
```

- [ ] **Step 5: Implement election and handoff**

Use one object stored under `com.alechilles.patchwork.coordinator.registry`. Synchronize all state transitions on that registry object. Candidate replacement requires its registration token. On election change: fence old epoch, stop accepting work, drain publication/reload, deactivate old bridge, activate and start new bridge, then publish the new active bridge. If activation fails, reactivate the old winner before returning failure.

- [ ] **Step 6: Run tests and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchworkRuntimeCandidateTest,PatchworkCoordinatorRegistryTest -Dit.test=ForeignClassLoaderElectionIT verify
git add runtime
git commit -m 'Feat: elect one Patchwork runtime process-wide'
```

## Task 8: Expose Embedded Bootstrap And Host Contributions

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/EmbeddedPatchworkBootstrap.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/EmbeddedPatchworkService.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkContributionHandle.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkHostContribution.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkMacroProvider.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkTargetAdapter.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkTargetExpectation.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkReloadRequest.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkReloadResult.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkReloadObservation.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkObservationOutcome.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkRuntimeHost.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/embedded/PatchworkRuntimeProviderHandle.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/embedded/EmbeddedPatchworkBootstrapTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/embedded/PatchworkContributionForwardingTest.java`

- [ ] **Step 1: Add ownership-change tests**

Bootstrap embedded v1, register a contribution, then register a newer standalone candidate. Assert the same service handle forwards generated-root queries, contribution registration, and observations to the active winner without Tamework re-registering. On close, remove only that provider's token and contribution handles.

- [ ] **Step 2: Add contribution conflict tests**

Reject duplicate `(hostPluginIdentifier, contributionVersion, macroId)` and duplicate adapter IDs deterministically. Verify contribution unregister drains in-flight adapter calls.

- [ ] **Step 3: Run and confirm failure**

```bash
./mvnw.cmd -pl runtime -Dtest=EmbeddedPatchworkBootstrapTest,PatchworkContributionForwardingTest test
```

- [ ] **Step 4: Implement the exact public contracts above**

Read runtime version from Maven `pom.properties`, then package implementation version, then fail startup with an actionable error; never silently report `0.0.0`. Provider IDs are `embedded:<plugin-id>` or `standalone:<plugin-id>`.

- [ ] **Step 5: Run tests and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=EmbeddedPatchworkBootstrapTest,PatchworkContributionForwardingTest test
git add runtime
git commit -m 'Feat: expose embeddable Patchwork runtime API'
```

## Task 9: Add Commands, Status, And Self-Test Ownership

**Files:**
- Create: `runtime/src/main/java/com/alechilles/patchwork/command/PatchworkCommandRoot.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/command/PatchworkStatusCommand.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/command/PatchworkReloadCommand.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/command/PatchworkSelfTestCommand.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/selftest/PatchworkSelfTestCase.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/selftest/PatchworkSelfTestPack.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/selftest/PatchworkSelfTestRunner.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/selftest/PatchworkSelfTestReloadHandle.java`
- Create: `runtime/src/main/java/com/alechilles/patchwork/selftest/PatchworkSelfTestResult.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/command/PatchworkCommandOwnershipTest.java`
- Create: `runtime/src/test/java/com/alechilles/patchwork/selftest/PatchworkSelfTestRunnerTest.java`

- [ ] **Step 1: Port self-test cases and add command ownership tests**

Assert only the active winner registers `/patchwork`, election handoff unregisters the previous owner before registering the next, non-winning providers expose no command, every administrative operation requires `patchwork.admin` with operator/admin defaults, and self-test cannot mutate production outputs.

- [ ] **Step 2: Define status output assertions**

Status must report active runtime version/origin/provider/protocol/source jar, passive candidates and election reasons, contribution IDs, neutral and legacy root state, last generation epoch/result, and target rows for generated, removed, hot-reloaded, adapter-reloaded, restart-required, stale, rollback-failed, skipped, and failed outcomes. It must not print condition source or expected values.

- [ ] **Step 3: Run and confirm failure**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchworkCommandOwnershipTest,PatchworkSelfTestRunnerTest test
```

- [ ] **Step 4: Implement commands and self-tests**

Make `/patchwork reload` the sole post-startup generation entry point. Run self-tests in an isolated temporary generated pack and remove it after the generated-parent containment check.

- [ ] **Step 5: Run tests and commit**

```bash
./mvnw.cmd -pl runtime -Dtest=PatchworkCommandOwnershipTest,PatchworkSelfTestRunnerTest test
git add runtime
git commit -m 'Feat: add Patchwork administration commands'
```

## Task 10: Implement And Package The Standalone Provider

**Files:**
- Create: `standalone/src/main/java/com/alechilles/patchwork/standalone/PatchworkPlugin.java`
- Modify: `standalone/src/main/resources/manifest.json`
- Modify: `standalone/src/test/java/com/alechilles/patchwork/standalone/StandalonePackagingIT.java`
- Create: `standalone/src/test/java/com/alechilles/patchwork/standalone/PatchworkPluginLifecycleTest.java`

- [ ] **Step 1: Add lifecycle tests**

Assert plugin setup bootstraps origin `STANDALONE`, start registers the candidate, shutdown closes the exact registration token, a newer embedded runtime wins by version, and standalone wins only an equal-version tie.

- [ ] **Step 2: Run and confirm failure**

```bash
./mvnw.cmd -pl standalone -am -Dtest=PatchworkPluginLifecycleTest test
```

- [ ] **Step 3: Implement the thin plugin wrapper**

`PatchworkPlugin` may own only the provider handle and lifecycle delegation. Keep all behavior in runtime. Ensure the shaded jar relocates no `com.alechilles.patchwork` classes and excludes duplicate signatures.

- [ ] **Step 4: Verify jar contents and commit**

```bash
./mvnw.cmd clean verify
jar tf standalone/target/patchwork-standalone-1.0.0.jar | grep -E 'PatchworkPlugin|EmbeddedPatchworkBootstrap|manifest.json'
git add standalone pom.xml
git commit -m 'Feat: package standalone Patchwork plugin'
```

## Task 11: Document Compatibility And Validate The Release Candidate

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Create: `docs/Patch-Format.md`
- Create: `docs/Embedding.md`
- Create: `docs/Runtime-Election.md`
- Create: `docs/Operations.md`

- [ ] **Step 1: Document patch author behavior**

Include both roots and precedence, all operations, multi-target rules, all condition forms, ModData trust/boundary rules, duplicate keys, examples, and the `TameworkSetting` retirement. Explain that legacy Tamework root discovery requires Tamework to be installed.

- [ ] **Step 2: Document embedding and operations**

Provide the exact Maven dependency and bootstrap contracts from this plan. Explain the election ordering, passive embedded copies, startup quarantine, target-local live transactions, restart-required targets, `/patchwork status|reload|selftest`, and rollback-failed recovery.

- [ ] **Step 3: Run the complete verification matrix**

```bash
./mvnw.cmd clean test
./mvnw.cmd clean package
jar tf runtime/target/patchwork-runtime-1.0.0.jar | grep 'com/alechilles/patchwork/embedded/EmbeddedPatchworkBootstrap.class'
jar tf standalone/target/patchwork-standalone-1.0.0.jar | grep 'com/alechilles/patchwork/standalone/PatchworkPlugin.class'
grep -R -n -E 'TameworkSetting|com\.alechilles\.alecstamework' runtime/src standalone/src || true
```

Expected: all tests pass; both classes are present in the correct artifacts; the final grep has no production dependency on Tamework and only deliberate compatibility text in tests/docs if any.

- [ ] **Step 4: Install the pinned runtime for Tamework integration**

```bash
./mvnw.cmd clean install
```

Confirm `~/.m2/repository/com/alechilles/patchwork/patchwork-runtime/1.0.0/patchwork-runtime-1.0.0.jar` exists.

- [ ] **Step 5: Commit the verified docs**

```bash
git add README.md CHANGELOG.md docs
git commit -m 'Docs: document Patchwork 1.0.0 contracts'
git status --short
```

Expected: clean Patchwork worktree. Continue with `2026-07-31-tamework-patchwork-integration.md` only after this reactor installs successfully.
