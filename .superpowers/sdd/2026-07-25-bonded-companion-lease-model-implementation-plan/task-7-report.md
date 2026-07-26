# Task 7 Report: Profile-first bonded companion panel

## Status

Completed on `feat/bonded-companion-lease-model` from base `d3a40c9c`.

## Implementation

- Added a bonded-only record source, presentation source, action service, and action router. The existing command-item handler now selects these collaborators only for bonded storage mode; generic roster behavior remains on its existing path.
- Card identity is a deterministic synthetic UUID derived solely from the bonded profile ID. Rendered rows retain the exact roster ID, profile ID, and profile revision used by summon, store, revive, and revive-quote requests.
- Bonded cards render STORED, ACTIVE, and DEAD state from durable profile views, including species, gender, role presentation, level, health, needs, breeding state, attachments, talents, and extension-owned detail such as Miniwyvern data. Raw role IDs are excluded from presentation.
- Unsupported or stale actions are not bound. The controller revalidates the current presentation before dispatch, and feature-managed bonded rows never fall through to legacy link/unlink/release/cull actions.
- Paid revive presentation and routing use the bonded quote/action API with the rendered revision.

## Narrow prerequisite correction

Capture already persisted the full `BondedCompanionSnapshot`, but the API view factory exposed only the shallow record columns. `BondedCompanionViewFactory` now decodes that persisted snapshot at the API/view boundary and merges its durable presentation fields into `BondedCompanionProfileView`. UI code never decodes persistence payloads and never reads a live NPC to fill a bonded card.

## TDD evidence

- RED: the initial required focused run failed with 26 missing-symbol compilation errors before the bonded panel types existed.
- RED: stale bonded summon routing failed with `expected: <0> but was: <1>` before controller revalidation.
- RED: the runtime bonded binder regression failed with a null generic-roster dereference before the bonded branch was moved ahead of generic binding.
- GREEN: required focused suite passed: 8 tests, 0 failures, 0 errors.
- GREEN: expanded focused suite passed: 18 tests, 0 failures, 0 errors.
- GREEN: binder/layout regression suite passed: 11 tests, 0 failures, 0 errors.

## Verification

- `./mvnw test -Dtest=BondedCompanionPanelRecordSourceTest,BondedCompanionPanelActionServiceTest,BondedCompanionPanelFeatureBinderTest` — PASS (8 tests).
- `./mvnw test -Dtest=BondedCompanionPanelFeatureBinderTest,LinkedNpcPanelCardLayoutTest` — PASS (11 tests).
- Expanded panel/view/controller suite — PASS (18 tests).
- `git diff --check` — PASS.
- ECS/player-access safety grep from `AGENTS.md` — no matches.
- `./mvnw test` — 3166 tests executed; 3161 passed, 1 skipped, 5 failed. The Task 7 `LinkedNpcPanelCardLayoutTest` failure was fixed and passes on rerun. Four unrelated source-text architecture assertions remain in untouched capture/VFX/relocation/spawner files:
  - `HytaleCaptureTameLiveBoundaryArchitectureTest.targetMutationDetachesSpawnAuthorityAndRequestsDetachedRoleChange`
  - `CaptureChannelVfxSystemTest.channelSupportsLegacyParticlesAndHomingMoteCadence`
  - `CommandNpcRelocationServiceTest.importRecoveryRunsOnlyForCleanExplicitRecallExhaustion`
  - `SpawnerFeatureHandlerTest.captureRejectsStackedSpawnerItemsBeforeMetadataWrite`
- `check-agent-docs.ps1` could not complete: the linked worktree does not contain the untracked root `AGENTS.md`; running against the main root then failed because the available PowerShell runtime lacks `System.IO.Path.GetRelativePath`.

## Remaining validation

No live in-game UI session was run for this isolated task. Documentation/changelog integration is left to the parent plan's integration/documentation task.

## Review fix round 1

### Corrections

- Added a caller-supplied action context carrying a frozen safe summon placement and a concrete live player-inventory boundary. The command panel now creates that context from its current `Player` and `Store`, uses it while quoting, and carries it through summon and revive actions.
- Replaced the Hytale bonded world's unconditional summon retry with a world-thread spawn through the existing exact projection executor. The adapter uses the durable profile ID, lease token, planned NPC UUID, source snapshot UUID, and frozen placement; generic operation types remain behind an items-layer boundary so the bonded domain's isolation guard stays intact.
- Quote affordability now reads the current inventory, and revive consumes the exact configured price before committing `DEAD -> STORED` through the real bonded store mutation.
- Profile availability now enforces the current roster policy, family, allowed role, feature flags, maximum-active capacity, and durable state. Active dismiss is enabled only in the lease's current world, while stored summon requires a matching safe placement.
- Capture now persists a base species label resolved from the NPC name key. The specialized bonded role remains separately available as `rolePresentation`; raw role IDs are not reused as species.
- Restored owner-command-family suppression of legacy link/remove actions even when no managed feature presentation exists.

### Root causes and TDD evidence

- Summon carried only a world name and the production world gateway always returned retry-required; the new integration test initially failed to compile because no action placement context or spawn-plan placement existed.
- Quote hard-coded `affordable=false`, revive always reported unavailable payment context, and the router discarded its live `Player` inventory; the integration test initially failed to compile against the missing inventory context and then exercises actual quote/consume behavior.
- Availability was derived only from profile state, active dismissal ignored the lease world, capture wrote `species=null`, and the view mapper used a humanized role as species. Focused view/panel assertions were added for each corrected boundary.
- The first broadened run exposed the restored spawn gateway's direct generic-operation import through `BondedCompanionPersistenceBoundaryTest`; that RED was corrected with the isolated spawn boundary and the guard passed on rerun.

### Integration coverage

`BondedCompanionPanelLifecycleIntegrationTest` uses the real schema manager, SQLite database/store, capture persistence adapter, transition service, projection durability, core API operations, facade, and panel action service. It verifies capture -> close/reopen (relog reconstruction) -> placement-backed summon -> store -> summon -> confirmed death -> affordable quote -> exact payment -> revive, plus maximum-active and wrong-world action disabling.

### Hytale source verification

- Hytale Workshop `0.5.7` confirmed the engine references in the new inventory context, projection spawn boundary, and bonded world gateway: zero missing types or calls in all three files.
- Workshop source for `com.hypixel.hytale.server.npc.entities.NPCEntity#getRoleName` / `#getNPCTypeId` confirms those methods expose role identity rather than a separate species field. Species is therefore frozen from the NPC's base name-key presentation at capture, while the durable role ID remains separately authoritative.

### Review-fix verification

- Focused lifecycle/view/binder/action suite: 9 tests, 0 failures, 0 errors.
- Architecture + lifecycle + binder rerun: 8 tests, 0 failures, 0 errors.
- `EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest`: PASS.
- Required ECS/player-access grep: no matches.
- `git diff --check`: PASS.
- Full `./mvnw test`: 3170 tests executed, 3165 passed, 1 skipped, with only the same four unrelated pre-existing failures listed above. All bonded companion and changed-area tests passed.
