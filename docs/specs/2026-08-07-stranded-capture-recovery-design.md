# Stranded Captured Companion Recovery Design

Date: 2026-08-07

## Goal

Recover companions stranded in filled capture items after a public Tamework
2.16.1 to replacement-persistence 3.x migration or a later partial rollback.
The primary compatibility target is a server that already completed migration
under Tamework 3.0.0, 3.0.1, or 3.0.2 and then upgrades to the fixed build.
Recovery must preserve the companion carried by the item, create at most one
live companion, and leave ambiguous evidence unchanged.

## Why Base-Game Assets Are Insufficient

The failure is an authority mismatch between an inventory artifact, canonical
SQLite lifecycle state, alias history, and snapshot history. Hytale item assets
can express the capture interaction, but they cannot atomically inspect or
repair those durable authorities. A small Java persistence workflow is
therefore required. Item behavior and presentation remain config-driven.

## Observed Failure Modes

### Released-public migration stranding

Public 2.16.1 filled items identify their source NPC alias but do not carry the
replacement profile and snapshot IDs. In affected saves, the legacy capture
snapshot survived import as non-current history while the legacy profile flags
said capture was inactive. Startup reconciliation consequently moved the
profile to `UNLOADED` with no location. The item still contains the captured
companion state, but ordinary release correctly rejects it because there is no
current `CAPTURED/CAPTURE_ITEM` snapshot.

### Replacement rollback split-brain

A filled replacement-era item can be newer than a restored canonical database.
The item then carries a valid profile, source alias, snapshot ID, and prior
release receipt that the restored database never observed. The database may
still hold an older `CAPTURED` version of the same profile. Ordinary exact
release correctly rejects the mismatched snapshot and alias.

The inverse mismatch is also possible: an old player inventory can restore a
filled item after the canonical profile was already released. Possession of a
decodable item therefore proves reconstructable state, not uniqueness.

## Scope and Non-Goals

This change will:

- operate directly on an already-migrated schema-1 `tamework-state.sqlite`,
  including after later startups classify that target as `EXISTING`;
- recover narrowly proven released-public migration artifacts automatically;
- reuse receipt-first inventory and entity durability guarantees from ordinary
  capture release;
- record one canonical active identity and fence superseded source identities.

This change will not:

- trust arbitrary filled-item metadata as canonical authority;
- read `tamework.sqlite` or any public database at runtime;
- create a missing canonical profile from an item in the first version;
- repair ambiguous modern `UNLOADED` artifacts or add an admin recovery tool;
- override ownership conflicts, quarantine, coop, roster, dead, lost, or active
  lifecycle authority;
- force-load an entity by UUID or scan unloaded chunks;
- provide a general database editor, refund tool, or revive-anything command.

## Chosen Recovery Policy

Ordinary release always runs first. Only its exact profile-authority conflict is
eligible for recovery analysis. Decode failures, unavailable persistence,
inventory conflicts, rejected submissions, or unknown live outcomes remain on
their existing paths and never fall through to recovery.

The shipped recovery mode is:

1. `LEGACY_IMPORTED` is automatic for a released-public item and a uniquely
   correlating imported capture-v1 history row on an initial imported
   `UNLOADED` profile.

2. `MODERN_CAPTURED_SUPERSEDE` handles the reporter's rollback split-brain
   shape: a complete newer same-profile item while canonical SQLite still
   retains an older exact `CAPTURED` snapshot.

Missing profiles and ambiguous legacy histories are reported but unsupported.
Modern `UNLOADED` profiles are also refused because they may still have an
unloaded world entity. None of these cases becomes implicit profile creation,
best-guess snapshot selection, or a general repair command.

Recovery eligibility uses the canonical non-current capture-v1 row as the
profile's migration lineage; replacement captures use capture-v2. It does not
depend on the transient startup `targetOrigin`. The legacy database, import
manifest lookup, original archive, and a second import run are neither required
nor consulted.

## Eligibility Fences

### Common fences

Every mode requires:

- replacement persistence at mutation readiness with completed loaded-identity
  bootstrap;
- one nonempty captured item in an exact actor, world, and inventory slot;
- a stable item hash covering item ID, quantity, durability, and canonical
  metadata;
- an existing canonical profile with matching role and owner evidence;
- a tamed-state claim compatible with canonical metadata;
- no active operation or lifecycle quarantine;
- no loaded entity for any canonical or item-claimed source alias;
- a decodable source-neutral full-state projection;
- a valid placement in the actor's current world.

An alias claimed by the item must resolve to the same profile, be absent, or be
historical for that profile. An alias already belonging to a different profile
is a hard conflict.

### Legacy imported mode

The item must have the released-public identity shape: source alias present,
with both replacement profile ID and capture snapshot ID absent. Mixed modern
metadata is invalid rather than legacy-compatible.

The item source alias must be the profile's current imported alias. A
retired-only alias match represents an item from before a later release and is
not automatically recoverable.

The canonical profile must have:

- lifecycle `UNLOADED` with `NONE` location;
- the initial imported/reconciled revision and generation shape, rather than a
  later ordinary unload;
- no current capture, coop, death, lost, roster, or recovery snapshot;
- exactly one non-current `capture` snapshot with payload version 1 that
  correlates with the profile, role, owner, and item-carried state.

The historical snapshot remains evidence, not a newly current capture
authority. The import mapped the reporter's current aliases shortly after the
older capture rows, so timestamp ordering is not treated as authority. Zero or
multiple viable capture-v1 candidates is an ambiguity and is refused.

### Modern captured-supersede mode

The item must carry complete replacement profile, source alias, and snapshot
IDs plus a release receipt and decodable item state. The canonical profile must
have lifecycle `CAPTURED/CAPTURE_ITEM` with one exact current capture snapshot,
but that canonical source differs from the item claim in the way ordinary
release rejected.

The held artifact and canonical row must agree on profile, owner, role, and
tamed identity. The item-claimed alias and snapshot must not currently belong
to another profile or another authoritative location. Recovery supersedes both
the canonical capture source and the orphaned item source in one operation.

## Architecture

### Recovery analyzer

A focused recovery analyzer classifies the immutable held artifact and reads a
bounded evidence view containing the canonical profile, alias history, capture
history, import lineage, and loaded-identity result. It returns either a typed
recovery plan or a stable refusal code. It performs no mutation.

The analyzer is used for player feedback, but it is not a transactional
authority. SQLite preparation rechecks all evidence from the persisted
operation request.

### Recovery author

A dedicated captured-artifact recovery author owns fallback orchestration,
projection construction, deterministic IDs, and submission. Recovery behavior
does not expand `SpawnerCapturedArtifactReleaseAuthor`, which remains the
ordinary-release orchestrator.

The author reuses the exact artifact adapter, ownership normalization, capture
snapshot codecs, placement resolver, and completion dispatch. Shared
receipt-first live behavior is extracted behind a small common boundary rather
than copied between operation kinds.

### Durable operation

The existing `companion_capture_release` operation accepts one optional,
backward-compatible legacy-recovery evidence block. Old payloads decode with
that block absent and keep their current behavior. The request freezes:

- profile ID, expected lifecycle revision, reconciliation generation, owner,
  and metadata revision/hash;
- canonical current alias and canonical capture snapshot when present;
- item-claimed source alias and snapshot ID;
- exact legacy history snapshot and payload hash when legacy mode applies;
- source-neutral full-state projection and hash;
- exact before/receipt artifacts, actor, world, and slot;
- target placement and deterministic target alias;
- distinct inventory and spawn receipts;
- request time and the complete evidence digest.

The profile is always an operation participant. An owner participant is added
when ownership is assigned or preserved, matching existing population and
containment behavior.

### SQLite preparation

Preparation opens one transaction and rechecks the mode-specific fences,
including exact history rows. It then lifecycle-fences the profile with the
operation ID. Any evidence drift returns a conflict before inventory or world
mutation.

The preparation layer must not select a different history candidate, normalize
an unexpected lifecycle, or convert a refusal into a weaker mode. The submitted
evidence is either still exact or the operation does not prepare.

### Live application

The recovery operation follows the existing receipt-first release sequence:

1. Probe the exact inventory slot for the source artifact or expected receipt.
2. Replace the source with the receipt using exact compare-and-set semantics.
3. Force-save and read back the actor receipt.
4. Apply or resolve one projection using the deterministic target alias and
   spawn receipt.
5. Force-save the target chunk and read back both durable receipts.

Every entity mutation runs on the owning world thread. No `Player` component or
mutable entity reference crosses an asynchronous boundary.

### Commit and publication

After both receipts are proven, the SQLite commit:

- retires the previous canonical alias;
- records the item-claimed source alias as retired for the same profile when it
  was absent or not already retired;
- retires the old current capture snapshot for modern captured supersession;
- preserves legacy history as non-current audit evidence;
- promotes the deterministic target alias;
- transitions the lifecycle directly to `ACTIVE/LIVE_ENTITY` at the target
  world;
- emits the existing capture-release, profile-projection, and
  lifecycle-projection outbox events.

The operation detail records whether the optional legacy-recovery evidence was
used. No new event codec, result type, or diagnostic subsystem is introduced.

Retired source aliases are durable stale-source tombstones. If a superseded
entity later loads, spawn-authority cleanup must identify the retired alias as
non-current for this profile and remove it without touching the recovered
target.

## Crash, Replay, and Concurrency Semantics

Operation and receipt IDs are deterministic from the exact artifact,
profile/evidence digest, actor/world/slot, and recovery mode.

- If the source item is unchanged, recovery can retry the exact replacement.
- If the inventory receipt is present, consumption is already proven.
- If the spawn receipt is present, the exact projection is reused rather than
  spawned again.
- If either readback is unavailable, the operation remains retryable.
- If inventory or projection evidence is contradictory, the outcome is unknown
  and the existing incident/quarantine containment path is used.
- No unknown outcome performs a blind refund or second spawn.

Different copies may create different operation IDs, but they share the same
profile participant and expected lifecycle revision. Only one can prepare.
After success, the profile is `ACTIVE` under a new alias, so all stale-item
replays fail eligibility. A later legitimate recapture creates a new current
snapshot and does not make an old recovery artifact eligible again.

## Feedback and Diagnostics

Automatic success sends a player notification that a stranded captured
companion was recovered and released. Refusals use stable, actionable messages
such as owner mismatch, ambiguous legacy history, competing live identity,
profile state not recoverable, or persistence not ready. The item remains
untouched for every pre-submission refusal.

The existing operation kind automatically participates in accepted, rejected,
completed, and failed diagnostics. No recovery-only diagnostic subsystem is
added.

## Persistence and Compatibility Impact

The generic operation, participant, alias, snapshot, incident, and outbox
tables already represent this workflow. No second database and no runtime
legacy adapter are introduced. No schema migration is allowed for this scoped
fix; if the existing identity store cannot express the required alias fencing,
the design must be revisited instead of expanding persistence schema.

The current `companion_capture_release` payload version and event remain
unchanged. Its optional recovery field is absent for ordinary releases, so
servers without stranded items continue using the normal path exclusively.

## Testing Strategy

Tests must invoke production behavior and assert observable outcomes. Do not add
source-shape, registration-presence, or raw-SQL-text guards.

Required regressions are deliberately limited:

1. Open a minimal already-migrated schema-1 fixture matching the stranded rows
   produced by 3.0.0-3.0.2, with runtime origin effectively `EXISTING` and no
   legacy database present. Release its exact legacy item and assert one entity
   projection, exact item consumption, lifecycle `ACTIVE`, target alias
   promotion, and preserved owner/full state.
2. Give a modern item a newer same-profile claim than an older canonical
   `CAPTURED` snapshot. Assert automatic supersession and one active target.
3. Replay a copied source artifact after success. Assert no second projection.
   Existing lifecycle-revision operation tests cover the generic concurrent
   preparation race and are not duplicated.
4. Refuse owner mismatch, alias-to-other-profile, ambiguous legacy histories,
   loaded sources, quarantined/active-operation profiles, and active, coop,
   roster, dead, or lost lifecycles. Assert inventory, lifecycle, aliases, and
   world effects remain unchanged.

The existing capture-release executor suite already proves inventory receipt,
spawn receipt, interruption, and durable readback ordering. Reuse that executor
without cloning its crash matrix into recovery-specific tests.

Run focused tests during red-green development, then:

```bash
bash ../gradlew :alecstamework:test
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

If runtime ECS or world-boundary classes change, also run
`EcsWriteSafetyGuardTest` and `AsyncThreadSafetyGuardTest` explicitly and verify
the shared release receipt suite.

## Documentation and Release Notes

Update capture-item documentation with automatic migrated recovery, refusal
conditions, and the requirement for a complete atomic save backup. Add a
player-facing changelog entry without bumping the version unless
`gradle.properties` is intentionally updated.

After implementation is verified, record the migration and rollback authority
lesson in the external Tamework persistence Lessons Learned notes.
