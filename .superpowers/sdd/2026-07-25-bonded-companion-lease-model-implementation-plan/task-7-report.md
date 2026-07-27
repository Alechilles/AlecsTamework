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

## Review fix round 4

### Root causes

- `HytaleBondedCompanionActionContextFactory` used `CombinedItemContainer.replaceAll` as though the returned list transaction were atomic. Hytale 0.5.7 source shows `ItemContainer.replaceAll` writes each replacement immediately while iterating and returns a successful list transaction. A late slot mismatch could therefore leave earlier slots debited, after which the old failure branch removed the pending receipt.
- The same adapter ignored the verified boolean returned by `markCharged()`. A full debit could be returned to the API without canonical charged evidence; a retry could neither recover that charge nor prevent another debit.
- The direct panel payment path had only pending/charged/compensating markers, but no durable quantity evidence for distinguishing a prepared attempt from a partial/full debit after a failed state transition.

### Corrections

- Added a focused `BondedCompanionChargeCoordinator` state machine. It admits a debit only after a prepared receipt has been read back, never debits a prepared/recovery/conflict operation twice, and compensates any observed partial/full noncanonical debit before allowing another action.
- Replaced slot-by-slot `replaceAll` charging with Hytale's `removeItemStack(..., allOrNothing=true, filter=false)`. The engine prevalidates the full combined inventory and performs the removal while holding all constituent container write locks.
- Prepared receipt keys now retain the operation's pre-debit item quantity. Immediate readback classifies the bounded operation-keyed marker as `PREPARED`, `DEBITED`, `CHARGED`, `COMPENSATING`, `COMPENSATED`, or `CONFLICT`. An unexpected partial result refunds only the measured debit, never the full quote.
- Failed charged transitions immediately enter receipt-first compensation. If the refund or receipt transition cannot finish synchronously, the prepared/compensating receipt remains on the player and retry resumes compensation instead of charging again.
- Successful compensation now leaves one bounded compensated tombstone for that operation. This prevents the same idempotency key from charging again after receipt-tagged refund delivery. Canonically committed charges still release their receipt after the SQLite mutation is durable.
- The core API now recognizes compensation-pending receipts returned directly by a fresh consume attempt, not only receipts recovered at the start of a retry.

### TDD evidence

- RED: `BondedCompanionChargeCoordinatorTest` initially failed compilation because the coordinator contract did not exist.
- RED: the compensation tombstone regression then failed compilation on the missing `COMPENSATED` state.
- GREEN: deterministic coverage now proves multi-slot contention leaves the untouched slot intact, an unexpected one-slot partial debit restores only that slot, a failed charged transition preserves recovery, and subsequent retries neither debit nor refund twice.

### Hytale source verification

- Hytale Workshop release `0.5.7`, `com.hypixel.hytale.server.core.inventory.container.ItemContainer#replaceAll`: replacements are installed one slot at a time inside the loop.
- Hytale Workshop release `0.5.7`, `com.hypixel.hytale.server.core.inventory.container.InternalContainerUtilItemStack#internal_removeItemStack`: `allOrNothing` performs a complete prevalidation before mutation.
- Hytale Workshop release `0.5.7`, `com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer`: the combined write action locks every constituent container for the operation.
- Engine reference validation found all changed receipt-plan references. Factory validation found all real engine calls; its one reported `CombinedItemContainer#inventory` miss is a static-analysis false association with the mod's private `PlayerInventory.inventory()` helper, and the Java compile passed.

### Review-fix verification

- Coordinator plus real SQLite lifecycle suite: 6 tests, 0 failures, 0 errors.
- Expanded bonded panel/payment/refund/receipt suite: 25 tests, 0 failures, 0 errors.
- Routing, bonded boundary, ECS/thread safety, replacement architecture, and forked crash matrix: 22 tests, 0 failures, 0 errors.
- Required ECS/player-access grep: no matches.
- `git diff --check`: PASS.
- Generated agent index rebuilt for the added source/test files.
- Full `./mvnw test`: 3,175 tests executed, 3,170 passed, 1 skipped, with only the same four unrelated pre-existing failures listed above. Every bonded/payment/changed-area test passed.
- `check-agent-docs.ps1` reached the known linked-worktree limitation and stopped because this worktree does not contain the externally supplied root `AGENTS.md`.

### Remaining validation

No live in-game inventory contention session was run. The atomicity contract is grounded in Hytale 0.5.7 source and covered through the deterministic coordinator seam; runtime behavior still warrants normal release smoke testing.

## Review fix round 5

### Root causes

- The aggregate receipt model could not safely represent multiple independent revival attempts. A same-operation loser could also refund a debit that the winning attempt had already committed.
- SQLite terminal outcomes expired on a wall-clock retention deadline, leaving a cross-store crash window in which player escrow could survive after the database evidence required to settle it had been pruned.
- Recovery ran from the raw player-add event before the `Player` component was guaranteed to be attached. It also trusted operation-only legacy markers, absent escrow, and null asynchronous gateway results too readily.
- Operation identity omitted the expected profile revision, and the original terminal-retention sentinel could collide with a valid caller-provided deadline. Historical failed operations were also accidentally included in terminal pinning.

### Corrections

- Replaced aggregate charging state with bounded per-operation escrow receipts. Canonical IDs include owner, profile, expected revision, and request identity; quotes count reserved escrow and terminal SQLite state is probed before mutable inventory gates.
- Same-operation `APPLIED` results now always consume the matching escrow. Different-operation terminal receipts are garbage-collected and recovered across reloads, while ambiguous legacy zero/partial/full states fail closed or quarantine without refunding unproven value.
- REVIVE `SUCCEEDED` and `REJECTED` rows are pinned until an outcome-aware acknowledgement CAS succeeds. Schema v5 adds `expected_revision`, migrates historical settled revival rows to the pin, excludes `FAILED`, and reserves `Long.MAX_VALUE` solely for unsettled cross-store evidence.
- Added post-attach `RefSystem` recovery that queues only world and owner UUID, re-resolves the live player component on the world thread, reconciles legacy evidence before absent-escrow acknowledgement, and leaves malformed, foreign-owner, null-stage, disappearing-player, or otherwise ambiguous cases fail closed.
- Split claim/probe logic into `SqliteBondedCompanionOperationClaims` and kept all new production collaborators within the repository size limits.

### TDD and verification evidence

- Focused schema, SQLite probe/recovery, operation-ID, escrow inventory/component, revive service, and panel lifecycle suite: 68 tests, 0 failures, 0 errors.
- ECS/thread, persistence-boundary, routing, paid-revival boundary, and process-crash guard suite: 39 tests, 0 failures, 0 errors.
- Full `./mvnw test`: 3,212 tests executed; 3,207 passed, 1 skipped, and only the same four unrelated baseline failures remained in capture/VFX/relocation/spawner tests. No bonded, payment, schema, or changed-area test failed.
- Required ECS/player-access grep returned no matches; `git diff --check` passed.
- Hytale source confirms the add-player event precedes component attachment, `World.execute(...)` supplies the required world-thread queue, and combined inventory mutation holds the constituent container locks.
- The generated agent index was rebuilt. `check-agent-docs.ps1` reached the known linked-worktree limitation because the externally supplied root `AGENTS.md` is not present inside this worktree.

### Remaining validation

No live in-game crash/rejoin session was run. Deterministic tests cover bounded player-add recovery, SQLite terminal retention, same-item concurrent mutation, shared 32-receipt capacity, 40 gateway cycles, component codec/save round trips, and legacy pending zero/partial/full quarantine behavior.

## Review fix round 6

### Root causes

- A player escrow could become durable before the first SQLite revival claim, but reconnect recovery only settled an already terminal claim. That crash window stranded paid reservations, and retrying through the current quote policy was not reliable when policy changed while the player was offline.
- Refund recovery moved the whole escrow in one pass. It had no durable per-slot cursor and concurrent refund callers could overlap save barriers, so a crash could replay already returned slots or observe out-of-order saves.
- Historical operation IDs flattened owner, profile, and request values into a non-injective key. Pagination could split a collision group, while null-profile/null-revision rows could neither acknowledge exactly nor leave the pinned retention sentinel. Unsafe singleton evidence therefore retried forever.
- Terminal operation pruning existed in the store but was not scheduled by bonded-companion maintenance.

### Corrections

- Full `RESERVED` escrow now exposes exact item and quantity proof. Recovery reconstructs the canonical request hash from that immutable proof and can atomically create or resume the exact SQLite revival claim without consulting current payment policy. `REFUNDING`, terminal, partial, and mismatched evidence cannot become claim proof.
- Refunds now transition through a durable `REFUNDING` phase, return at most one escrow slot, save the actor, and only then advance to the next slot. A per-actor single-flight coordinator serializes settlement and resumes the persisted cursor after reconnect.
- Legacy settlement loads complete flattened-key groups, including finite partners, before applying the page limit. Colliding or otherwise unsafe groups move only their pinned database evidence to finite quarantine; recovery never looks up, refunds, consumes, or deletes player escrow by the ambiguous key.
- Acknowledgement now uses exact null-safe profile and revision identity and changes only the pinned sentinel to the first finite retention deadline. Duplicate acknowledgements verify the already-finite row without extending its lifetime, and operation revision checks no longer treat null as a wildcard.
- Bonded maintenance now prunes finite terminal operations on its normal schedule. Historical schema-v5 null-profile rows can be acknowledged or finitely quarantined and eventually garbage-collected.
- Settlement and legacy-SQL responsibilities were extracted into focused collaborators. Changed production classes remain within the 500-line target (`HytaleBondedCompanionEscrowInventory` 481 lines, retention store 425, recovery service 371, settlement coordinator 190, legacy payment store 134).

### TDD evidence

- RED: crash recovery, refund cursor, and claim-proof tests initially failed compilation on the missing `REFUNDING` phase and exact receipt metadata.
- GREEN: deterministic tests now cover a reloaded full reservation before the first claim, an exact matching pending claim, a mismatched pending claim, policy changes, persisted partial-refund resume, concurrent refund callers, component codec round trips, collision groups crossing the page limit, finite collision partners, historical null-profile rows, exact acknowledgement CAS, duplicate acknowledgement, unsafe legacy singleton quarantine, and scheduled pruning.
- Review found and tests reproduced two additional defects before completion: non-reserved phases could be mistaken for paid claim proof, and concurrent refunds could overlap the per-slot durability fence. Both paths now fail closed or serialize respectively.

### Review-fix verification

- Final focused escrow, recovery, retention, schema, and maintenance suite: 60 tests, 0 failures, 0 errors.
- Size guards plus escrow/recovery rerun after collaborator extraction: 49 tests, 0 failures, 0 errors.
- Expanded changed-area and ECS/thread-safety suite: 90 tests, 0 failures, 0 errors.
- Required ECS/player-access grep: no matches. `git diff --check`: PASS.
- Generated agent index rebuilt for the four added production collaborators.
- `check-agent-docs.ps1` reproduced the known linked-worktree limitation and stopped because this worktree does not contain the externally supplied root `AGENTS.md`.
- Full `./mvnw test`: 3,221 tests executed, 3,216 passed, 1 skipped, with only the same four unrelated baseline failures in capture architecture, channel VFX, relocation recovery, and stacked spawner validation. Every bonded/payment/schema/retention/changed-area test passed.

### Remaining validation

No live in-game crash/rejoin or inventory-contention session was run. Deterministic restart, reconnect, partial-refund, collision, exact-CAS, and pruning tests cover the remediated crash windows; normal release smoke testing remains appropriate.
