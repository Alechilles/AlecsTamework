# Persistence Consolidation Baseline

Status: analysis baseline for the persistence replacement program
Captured: 2026-07-22
Public compatibility boundary: Tamework `v2.16.1`, commit
`acb12aaae05e3568b1bf787a1d6d17d009df5686`, released 2026-06-30
Current development baseline: schema v9 on the 2026-07-22 source tree

## Purpose

This document records what the current persistence implementation actually owns,
which invariants must survive its replacement, and which structures are accidental
complexity. It is a source-facing baseline, not an endorsement of the current
architecture.

The implementation plan is maintained in:

`C:\Users\22ale\AppData\Roaming\Hytale\My Mod Docs\Planned Features\Tamework`

The replacement must be evaluated against this baseline before old code or tables
are removed.

## Executive conclusion

Tamework does not currently have one persistence subsystem. It has several
partially overlapping persistence systems that share a SQLite file, a write queue,
and a runtime service locator:

- stable companion identity and UUID aliases;
- profile metadata, snapshots, and legacy state flags;
- old coop slots plus managed-coop authority, resident, claim, import, and
  lifecycle stores;
- owner population authority, admission operations, reconciliation evidence,
  scan sessions, and in-memory indexes;
- capture, recovery, group, provisioning, command-roster, and timed-summon
  journals;
- extension API data;
- incidents, storage health, quarantines, circuits, coverage, and recovery
  evidence;
- JSON settings and announcement files with independent caching and atomic-write
  behavior.

The core failure is not that SQLite is inappropriate. The failure is that each new
feature has implemented its own variation of:

1. canonical state;
2. an operation state machine;
3. cross-table transaction assembly;
4. crash recovery;
5. cache/index publication;
6. startup readiness;
7. health and quarantine behavior;
8. shutdown draining.

That duplication made every new feature another distributed-systems exercise
inside one process.

The replacement should preserve the hard-won invariants and discard the duplicated
mechanisms. It should not preserve the current class graph or unreleased schema
history.

## Scale and growth

The 2026-07-22 source tree contains:

| Area | Files | Lines |
| --- | ---: | ---: |
| Production Java | 1,658 | 323,382 |
| Test Java | 794 | 117,062 |
| Direct `persistence` package | 189 | 38,773 |
| `persistence.sqlite` alone | 121 | 31,657 |
| Ownership and reconciliation | 59 | 10,607 |
| Items and command feature package | 310 | 77,151 |

Direct persistence grew as follows:

| Date | Files | Lines |
| --- | ---: | ---: |
| 2026-07-01 | 18 | 5,994 |
| 2026-07-10 | 18 | 6,013 |
| 2026-07-18 | 92 | 23,051 |
| 2026-07-22 | 189 | 38,773 |

From 2026-07-01 through 2026-07-22, persistence-heavy paths accumulated 260
commits and 128,169 changed lines: 108,482 additions and 19,687 deletions.
The highest-churn day, 2026-07-11, had 81 related commits.

This was a compression problem as much as a design problem. Multiple authority,
recovery, resilience, and feature systems were created in less than two weeks,
before a common protocol could stabilize.

## Release boundary

The last public release is `v2.16.1`. Its persistence package contained 18 files
and 5,994 lines. Its `SqliteSchemaMigrator` supported schema v2 through v4.

The public schema contained these domain tables:

1. `npc_profiles`
2. `npc_uuid_aliases`
3. `npc_tool_links`
4. `npc_snapshots`
5. `coop_slots`
6. `profile_states`
7. `api_profile_data`

It also contained `schema_migrations`.

Schema v4 added `coop_slots.state_snapshot_json`. The managed-coop, owner
population, resilience, capture-attempt, provisioning, population-group,
command-roster, and timed-summon schemas are all unreleased.

Consequences:

- Automatic compatibility is required for a valid public v2-v4 SQLite database,
  with v4 as the primary release fixture.
- Post-2026-06-30 tester databases are not a compatibility surface. Testers will
  restore an old backup or create a new world.
- The replacement must detect a v5-v9 development database and refuse to import it
  without modifying it. Silent reset is forbidden.
- Migrations v5-v9 must not become permanent production migration history.
- The July code and tests remain valuable as an invariant corpus, fault corpus,
  and behavioral specification.

## Current schema inventory

The current v2-v9 lineage contains 50 unique tables. The exact tables should be
re-inventoried mechanically before removal, but their responsibilities group as
follows.

### Public v2-v4 lineage

- `schema_migrations`
- `npc_profiles`
- `npc_uuid_aliases`
- `npc_tool_links`
- `npc_snapshots`
- `coop_slots`
- `profile_states`
- `api_profile_data`

### Managed-coop import and lifecycle

- `managed_coop_import_sessions`
- `managed_coop_import_sources`
- `coop_import_conflicts`
- `coop_lifecycle_operations`
- `managed_coop_authority`
- `managed_coop_residents`
- `managed_coop_uuid_claims`
- `npc_recovery_operations`

### Owner population and reconciliation

- `companion_population_operations`
- `companion_population_reconciliation`
- `companion_population_reconciliation_evidence`
- `companion_population_scan_session`
- `companion_population_state`

### Resilience and control plane

- feature circuit audit records;
- feature circuit state;
- persistence incident scopes;
- persistence incidents;
- quarantine records;
- storage probe records.

### Capture and post-v7 lifecycle features

- capture attempts;
- capture tombstones;
- capture cooldowns;
- capture refund claims;
- population group assignments;
- population group classifications;
- population count evidence;
- population event receipts;
- population group operations;
- provisioning operations;
- provisioning command links.

### Command feature state

- command family records;
- command roster membership;
- command roster operations;
- timed summon sessions;
- timed summon operations;
- timed summon snapshots;

The captured development lineage also contained five tables for an unreleased
command-panel payment experiment. That experiment has since been rejected and
deleted rather than becoming part of the replacement target; see ADR 0013.

Table count is not itself the defect. The defect is that multiple tables make
independent claims about the same companion lifecycle without one enforceable
canonical transition.

## Authority map

### Stable identity

Current authorities:

- `npc_profiles.profile_id`
- `npc_profiles.current_npc_uuid`
- `npc_uuid_aliases`
- planned identity leases used before ECS insertion
- `CompanionIdentityResolver`
- loaded-NPC identity indexes

Required invariant:

> A logical companion has one stable profile ID. Runtime NPC UUIDs are projections
> that may rotate. A UUID may resolve to at most one profile, and a new UUID must
> be leased before the corresponding live entity can become authoritative.

Problems:

- `NpcProfileRepository` combines identity, aliasing, metadata, snapshots, legacy
  state flags, tool links, observer callbacks, and identity leases.
- Stable identity rules are therefore entangled with unrelated record updates.
- Database authority and in-memory identity projections have separate publication
  paths.

Replacement boundary:

- one focused identity port;
- one durable profile record;
- one alias/lease store;
- read-only, rebuildable identity projections outside the transaction.

### Companion lifecycle and location

Current claims include:

- `profile_states` capture/death/lost/coop booleans;
- active `npc_snapshots`;
- `coop_slots`;
- managed-coop authority and resident rows;
- `companion_population_state.lifecycle_state`;
- command roster `command_state`;
- timed summon `summon_state`;
- provisioning records;
- live ECS presence;
- in-memory population and coop indexes;
- incomplete operation journals.

Current `CompanionLifecycleState` values are:

- `ACTIVE`
- `UNLOADED`
- `CAPTURED`
- `COOP`
- `DEAD_REVIVABLE`
- `LOST`
- `RESTORING`
- `STORING`
- `ROSTER_STORED`
- `UNKNOWN_DORMANT`
- `PROVISIONED_DORMANT`
- `RELEASED`

Required invariant:

> For capacity and duplication decisions, exactly one durable row is the canonical
> lifecycle/location claim for a profile. Domain detail tables can explain the
> state but cannot independently override it.

Required transition invariant:

> Any operation that changes owner, lifecycle, or durable location compares an
> expected revision and commits the new lifecycle revision atomically with its
> required domain detail, operation evidence, and projection event.

Problems:

- old and new lifecycle authorities coexist;
- domain services manually define precedence;
- recovery can reconstruct different answers depending on which repository is
  consulted first;
- `target_context_json` acts as an untyped cross-domain message bus;
- some in-memory authority is published before final durable commit.

### Owner population

Current authorities and projections:

- `companion_population_state`;
- admission operation rows;
- reconciliation sessions and evidence;
- count event receipts;
- `OwnerPopulationIndex`;
- `CompanionIdentityResolver`;
- claim occupancy and loaded-NPC indexes;
- sealed live/disk scan evidence.

Required invariants:

- no capacity-increasing transition completes without a durable admission claim;
- a source finalization is exact, idempotent, and correlated to the target
  transition;
- an owner/profile transition uses optimistic revision checks;
- absence is not evidence unless the relevant scan is sealed, boot/generation
  scoped, and complete across disk and live seams;
- generation `0` is valid;
- negative world timestamps are valid; `0` is the only unset sentinel.

Current strength:

The owner-population subsystem contains many of the most important correctness
discoveries in the codebase: durable preparation, exact finalization, sealed
absence evidence, reconciliation fingerprints, owner-scoped quarantine, and
optimistic revisions.

Current weakness:

`UnifiedPopulationCompositeStore` and admission coordinators assemble these rules
with managed coop, groups, dormant profiles, and extension behavior manually.
Their correctness does not automatically transfer to the next feature.

### Coop

Current authorities:

- public `coop_slots`;
- managed-coop authority;
- managed-coop resident rows;
- UUID claims;
- import sessions and conflict rows;
- coop lifecycle operations;
- owner population lifecycle rows;
- multiple in-memory coop indexes plus a composite trust epoch.

Required invariants:

- a resident occupies at most one authoritative coop slot;
- a slot has at most one authoritative resident;
- coop state and owner population state commit together;
- UUID claims cannot be reused by a different resident;
- release/capture is idempotent across restart;
- stale or absent live evidence cannot silently evict a durable resident;
- importer conflicts fail closed and remain diagnosable.

Current weakness:

Population operations parse JSON target context in order to mutate managed-coop
tables inside the same transaction. This preserves atomicity locally but creates a
hidden, untyped dependency between domain stores.

### Capture, death, lost, recovery, and refunds

Current authorities:

- profile state flags and active snapshots;
- capture attempt/tombstone/cooldown/refund tables;
- death/lost repositories;
- NPC recovery operations;
- population lifecycle operations;
- command roster and timed summon state;
- live inventory/source evidence.

Required invariants:

- preparation is durable before irreversible live mutation;
- source spending and target creation/finalization are correlated and idempotent;
- crash recovery distinguishes not-applied, applied, committed, and unknown;
- a refund is claim-based and can be granted at most once;
- restoring a companion cannot create a second authoritative presence;
- live mutation evidence is explicit, not inferred from mere absence;
- death/revival and command membership change atomically where both are required.

Current weakness:

Handlers manually stitch several repositories and state machines. Recovery policy
varies by feature, and some old repository reads flatten a storage error into
“not found.”

### Command roster, timed summon, provisioning, and free restoration

The command-roster, timed-summon, and provisioning features are unreleased but
represent real intended behavior. Free companion restoration predates this
development cycle and remains the supported death-recovery path.

Required invariants:

- one durable command-family identity;
- one profile occupies at most one roster position within its family;
- timed summon leases have one terminal owner and survive restart;
- roster-stored, summoned, dead, and provisioned states agree with canonical
  lifecycle;
- free restoration applies the persisted death snapshot to the same profile
  exactly once;
- delayed callbacks carry stable IDs and re-resolve live state on the world thread.

Current weakness:

The v9 repositories each implement their own operation models. Tests are much
thinner than for population and managed coop. `CommandTimedSummonRepository`
maintains an internal session cache and can update it from transaction-scoped work
before the outer transaction commits.

### Extension API data

Current authority:

- `api_profile_data`;
- post-v7 API data operation records.

Required invariants:

- extension data is keyed by stable profile ID, namespace, and key;
- extension writes participate in the same unit of work when lifecycle correctness
  depends on them;
- arbitrary extension payloads cannot mutate core lifecycle authority;
- codec/version/read failures are distinct from absent data.

### Configuration files

Current authorities:

- `TameworkSettingsStore`;
- `TameworkSettingsAnnouncementStore`;
- config override and staging files;
- static caches and file snapshots.

Required invariants:

- writes use a same-directory temporary file, flush, and atomic replacement where
  supported;
- readers observe a complete version, not partial JSON;
- cache invalidation/versioning is explicit;
- configuration persistence cannot block or corrupt companion lifecycle storage.

Replacement boundary:

Configuration files remain a separate bounded context. They should share a small
atomic JSON file primitive but must not be folded into the lifecycle database.

## Operation and transaction inventory

The current schema has at least nine tables explicitly named `*operations`, plus
other journals with equivalent semantics. Their state machines differ.

Examples include:

- population: `PREPARED`, `APPLYING`, `APPLIED`, `COMMITTED`,
  `COMPENSATING`, `RETRYABLE`, `FAILED`;
- coop lifecycle: its own transition set;
- NPC recovery: its own claim and finalization set;
- capture attempts: its own source-spend and terminal states;
- population groups: its own operation states;
- provisioning: its own operation states;
- command roster: its own operation states;
- timed summon: its own operation states;

The shared need is one operation envelope and transaction protocol, not one giant
domain operation class.

Domain payload and recovery classification must remain typed and domain-specific.
The shared envelope should own:

- operation ID and idempotency key;
- operation kind and schema version;
- owner/profile/tool/coop scopes;
- expected canonical revision;
- protocol phase;
- attempt/lease data;
- failure class and diagnostics;
- timestamps;
- durable completion evidence;
- projection/outbox sequence.

## Write path findings

`PersistenceWriteQueue` currently:

- runs one daemon writer;
- batches up to 256 accepted tasks;
- may place unrelated tasks in one SQLite transaction;
- retries busy failures up to three times;
- drains accepted work during close with a short default deadline;
- treats close timeout as an unknown outcome.

Metadata exists but is almost unused:

- approximately 90 production calls use `submitTracked`;
- three use `submitWithCompletion`;
- approximately 14 use the older `submit` path;
- normal string-based submissions become `PersistenceOperationMetadata.legacy`;
- only one production path was found constructing rich metadata.

Consequences:

- most operations report the generic storage domain;
- scopes, readback, fences, and atomic groups are absent;
- v7 scoped incident handling cannot identify most v8-v9 work;
- unrelated operations can share an unknown transaction outcome;
- legacy after-commit publication failures are not fully represented in the
  incident system.

Replacement requirements:

- one submitted command equals one transaction unless an explicit atomic group is
  declared;
- operation metadata is mandatory and typed;
- repositories never open their own connection inside a coordinated unit of work;
- projection publication is represented by a durable outbox row in the same
  transaction;
- after-commit callback failure cannot erase the fact that publication is pending;
- unknown commit outcome always uses durable readback before retry or compensation.

## Read path findings

The codebase has a bounded two-thread `PersistenceReadExecutor`, but many
repositories still open synchronous connections directly. Read contracts differ:

- some return explicit result types;
- some throw;
- older profile, capture, coop, death, lost, and API-data methods catch storage
  errors and return `null` or an empty collection.

This collapses two different facts:

1. no durable row exists;
2. durable state could not be read.

That ambiguity is unsafe for spawning, recovery, cleanup, and capacity decisions.

Replacement requirements:

- all reads return a typed success/absent/failure result or throw one typed storage
  exception at the application boundary;
- an empty collection is only a successful empty query;
- authoritative reads declare consistency and revision;
- read executor saturation and timeout are observable;
- no gameplay feature imports SQLite implementation classes.

## Projection and cache findings

Current in-memory projections include:

- `OwnerPopulationIndex`;
- `CompanionIdentityResolver`;
- `ClaimOccupancyIndex`;
- managed-coop resident and lifecycle indexes;
- managed-coop composite trust epoch;
- loaded-NPC identity index;
- timed summon session cache;
- command roster action cache;
- suppression, retirement, coverage, quarantine, and circuit registries.

Required invariant:

> Every in-memory index is a disposable projection. It can be rebuilt from durable
> canonical state plus a monotonic projection stream. It cannot become the sole
> evidence that a durable transition completed.

Known violation class:

Some paths publish a conservative in-memory claim before final durable commit in
order to prevent concurrent over-admission. This solves a real race but creates
publication/durability ambiguity and a recovery obligation. Other paths mutate a
cache while still inside an outer transaction.

Replacement direction:

- admission reservations must be durable or owned by one serialized application
  coordinator;
- canonical updates and projection events commit together;
- projectors consume events only after commit;
- projectors acknowledge sequence numbers;
- startup rebuilds from canonical tables before accepting mutations;
- cache mutation inside a database transaction is prohibited by architecture test.

## Startup, readiness, recovery, and shutdown findings

Startup is currently assembled manually in `Tamework.java` and
`OwnerPopulationRuntime`. It includes:

- database bootstrap and migration;
- legacy import;
- stale coop repair;
- owner population bootstrap and reconciliation;
- scoped recovery;
- capture recovery;
- population group recovery;
- provisioning recovery;
- timed summon recovery;
- a later timed-summon retry after worlds load;
- feature capability activation.

Readiness is spread across booleans, health services, coverage registries,
quarantines, circuits, and feature-specific checks. It is possible for one layer
to appear ready before a dependency is publish-ready.

Shutdown currently closes maintenance, storage recovery, resilience, reads,
writes, incident journal, and health in a mostly manual order. Feature callbacks
and world-thread work can outlive some persistence collaborators.

Replacement requirements:

- a static feature descriptor registry is the source of startup dependencies,
  schema ownership, recovery handler, projection, health scope, and shutdown
  participation;
- a dependency DAG calculates readiness;
- mutations open only after canonical load, recovery, projection catch-up, and
  required world evidence;
- shutdown phases are `STOP_ADMISSION`, `QUIESCE_CALLBACKS`, `DRAIN_FEATURE_WORK`,
  `DRAIN_WRITER`, `CHECKPOINT`, `CLOSE_READS`, `CLOSE_CONTROL`;
- each phase has a deadline and a durable/visible failure outcome;
- no generic background callback may submit after its feature is quiesced.

## Resilience findings

The current resilience work contains valuable concepts:

- global storage health;
- scoped incidents;
- quarantines;
- feature circuits;
- recovery coverage;
- exact verifier evidence;
- global fail-closed behavior for unknown or unbounded outcomes.

But the catalog and wiring lag feature growth:

- persistence domains primarily describe older population and coop features;
- v8-v9 command, group, provisioning, timed summon, and death-restoration paths
  do not have equivalent first-class domain coverage;
- failure reason catalogs skew toward older workflows;
- scoped verifier wiring is concentrated on owner population and managed coop;
- evidence dimensions do not consistently cover newer feature readiness.

Replacement direction:

- keep global storage mode, durable incidents, scoped quarantine, and exact
  recovery;
- derive feature scopes and recovery registration from one feature descriptor;
- do not maintain independent hand-written domain, circuit, evidence, and
  readiness catalogs;
- treat unknown transaction outcome, unreadable canonical state, failed fencing,
  or unbounded scope as global read-only until exact readback succeeds.

## Coupling findings

At the captured baseline:

- 183 non-persistence classes directly import the SQLite implementation package;
- those classes contain approximately 565 direct imports;
- about 140 direct runtime/repository getter calls occur outside persistence;
- `TameworkPersistenceRuntime` is an 847-line service locator;
- `Tamework.java` is 3,860 lines and manually composes persistence startup and
  feature recovery;
- persistence itself imports items, ownership, public API, and metrics types,
  creating bidirectional domain coupling.

Largest persistence classes include:

| Class | Lines |
| --- | ---: |
| `CommandTimedSummonRepository` | 1,269 |
| `NpcProfileRepository` | 1,140 |
| `CaptureAttemptRepository` | 1,135 |
| `TameworkSettingsStore` | 994 |
| `TameworkPersistenceRuntime` | 847 |
| `CoopLifecycleOperationTransactions` | 811 |
| `DeathRepository` | 719 |
| `PopulationGroupRepository` | 700 |
| `CoopLedgerRepository` | 651 |
| `ManagedCoopResidentTransactions` | 624 |
| `ApiProfileDataRepository` | 617 |
| `LostRepository` | 533 |

Replacement requirements:

- consumers depend on domain ports, not `persistence.sqlite`;
- SQLite adapters depend inward on domain records and ports;
- application use cases own cross-domain orchestration;
- transaction participants receive a unit-of-work session;
- runtime composition exposes domain facades rather than repository getters;
- architecture tests forbid new direct SQLite imports outside adapter and
  composition packages.

## Test baseline

The full test suite on the captured tree reports:

- 3,625 tests run;
- 0 failures;
- 0 errors;
- 2 skipped;
- Maven build success.

Persistence-related coverage is substantial but uneven:

- 91 persistence test files with approximately 337 tests;
- 67 ownership test files with approximately 397 tests;
- 179 items test files with approximately 857 tests;
- 81 test files open SQLite or a connection manager;
- 143 test files mention restart, recovery, or crash behavior;
- 68 architecture tests rely on source-text checks;
- only one suite performs a real child-process crash/restart;
- rich non-legacy write metadata has very little direct coverage;
- v9 migration and command-family feature repositories have comparatively thin
  test counts.

The passing suite proves that many local contracts work. It does not prove that
the combined system has one authority or one recovery protocol.

## Invariants to preserve

The following are non-negotiable even when their current implementations are
deleted:

1. Stable profile identity is distinct from rotating runtime UUID.
2. Identity must be reserved before a live entity becomes authoritative.
3. At most one canonical lifecycle/location row exists per profile.
4. Owner/capacity/lifecycle changes use optimistic revisions.
5. Preparation precedes irreversible live mutation.
6. Exact source finalization is idempotent and correlated.
7. Coupled authorities commit in one transaction.
8. Unknown commit outcome requires readback, never blind retry.
9. Durable commit and projection publication are separate acknowledged phases.
10. In-memory indexes are rebuildable projections.
11. Absence requires sealed, generation-scoped evidence.
12. Sidecars are repaired by positive contradiction, not mere absence.
13. Generation `0` is valid.
14. Negative world timestamps are valid; only `0` means unset.
15. Economic reservations and refunds are claim-based and idempotent.
16. Scoped quarantine may contain a known bounded failure.
17. Unknown scope, unreadable canonical state, or failed fencing is global
    read-only.
18. Delayed work carries IDs and re-resolves live state on the world thread.
19. Shutdown stops admission before draining persistence.
20. A storage read failure is never represented as “not found.”

## What should be retained, replaced, and deleted

### Retain as behavior

- SQLite with foreign keys, WAL, and full durability for authority journals;
- stable profile IDs and UUID aliases;
- planned identity lease concept;
- optimistic revisions;
- durable prepare/apply/finalize protocols;
- exact source finalization;
- sealed reconciliation evidence;
- scoped quarantine and global fail-closed rules;
- single-writer serialization;
- process-crash and repository fixtures;
- public v2-v4 data import;
- configuration files as a separate persistence context.

### Replace structurally

- 50-table v2-v9 evolution with a fresh schema lineage;
- multiple lifecycle authorities with one canonical lifecycle row;
- per-feature operation protocols with one shared envelope and typed domain
  handlers;
- direct repository getters with domain ports;
- implicit queue batching with explicit units of work;
- callback publication with durable outbox publication;
- null/empty-on-error reads with typed results;
- hand-wired startup/readiness/recovery with a feature descriptor DAG;
- scattered shutdown calls with one lifecycle coordinator;
- duplicated JSON atomic-write code with one file primitive;
- untyped `target_context_json` coordination with typed transaction participants.

### Delete after replacement gates pass

- production migrations v5-v9;
- legacy-metadata write submission APIs;
- direct SQLite imports from gameplay packages;
- old per-feature operation repositories and recovery loops;
- concurrent old/new lifecycle authorities;
- in-transaction cache mutation;
- service-locator repository getters;
- obsolete compatibility constructors and file-backed runtime paths;
- superseded resilience catalogs that are derived from the new descriptor registry.

Deletion must occur by dependency slice, after a mechanical reference search and
the replacement slice’s crash/restart gates pass. It must not be a mass deletion
before parity evidence exists.

## Baseline architecture gates

The replacement program should add executable checks for:

- no `persistence.sqlite` import outside approved adapter/composition packages;
- no repository opens a connection when a unit-of-work session is supplied;
- no public read method returns `null` or converts storage failure to empty;
- no cache/index mutation occurs inside transaction callbacks;
- no string-only or legacy write submission exists outside the kernel;
- no untyped JSON field is used to select a cross-domain transaction participant;
- every feature descriptor has schema owner, scopes, recovery handler, projection,
  startup dependencies, and shutdown hook;
- every operation kind has crash-boundary tests;
- every canonical transition advances one lifecycle revision;
- every projection event is written in the same transaction as its canonical
  update;
- unsupported development schemas fail without modifying source data.

## Files and entry points to revisit during implementation

Primary runtime composition:

- `src/main/java/com/alechilles/alecstamework/Tamework.java`
- `src/main/java/com/alechilles/alecstamework/persistence/sqlite/TameworkPersistenceRuntime.java`
- owner population runtime composition under
  `src/main/java/com/alechilles/alecstamework/ownership`

Kernel and schema:

- `src/main/java/com/alechilles/alecstamework/persistence/sqlite/SqliteConnectionManager.java`
- `src/main/java/com/alechilles/alecstamework/persistence/sqlite/SqliteSchemaMigrator.java`
- `src/main/java/com/alechilles/alecstamework/persistence/sqlite/PersistenceWriteQueue.java`
- persistence read executor and operation metadata packages

Canonical-state candidates:

- `NpcProfileRepository`
- companion population repository/store classes
- managed-coop repositories and transaction helpers
- capture/death/lost repositories
- command roster, timed summon, and provisioning repositories

Control plane:

- persistence health, diagnostics, incidents, recovery, and feature circuit
  packages

Configuration files:

- `TameworkSettingsStore`
- `TameworkSettingsAnnouncementStore`
- config override/staging file utilities

## Baseline completion criteria

This baseline can be declared superseded only when:

- the fresh schema has an explicit authority map;
- every invariant above maps to production code and at least one test;
- v2.16.1 fixture import is verified;
- v5-v9 input refusal is verified as non-destructive;
- all gameplay packages depend on ports rather than SQLite adapters;
- old and new runtimes cannot be active in the same process;
- the old implementation has been removed or isolated as an offline import fixture;
- live copied-save rehearsal and crash-boundary tests pass.
