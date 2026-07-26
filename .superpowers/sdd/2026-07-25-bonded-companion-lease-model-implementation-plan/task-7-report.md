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
