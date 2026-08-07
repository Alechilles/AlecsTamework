---
title: "Persistence, SQLite, and Data Paths"
order: 1
published: true
draft: false
---
# Persistence, SQLite, and Data Paths

Tamework uses one replacement persistence lineage. Its canonical database is
`tamework-state.sqlite`, and that lineage begins at schema version 1.

`TameworkPersistenceComposition` owns the only production bootstrap and facade
bundle. Gameplay code submits typed operations through focused authors; it does
not open connections or write tables directly.

## Canonical companion model

Each persisted companion has one stable profile ID. Live entity UUIDs are
replaceable aliases and must not be used as cross-mod identity keys. One
canonical lifecycle row answers where the companion is; feature detail and
snapshots cannot independently declare a competing lifecycle.

The replacement persistence-backed flows are:

- canonical profile identity and live UUID aliases;
- command links and canonical profile snapshots;
- filled-spawner capture and release;
- configured-coop capture/release of live NPCs and eligible captured items;
- durable owner population and role-defined population groups;
- command-family rosters and timed summon/storage leases;
- idempotent dormant provisioning and activation;
- resolved capture-attempt consumption and tame-and-command-link capture;
- free legacy restoration and exact paid roster revival;
- namespaced profile extension data.

There is one capture operation kind, one filled-item release operation, one
coop-capture operation for live and item sources, and shared restoration
machinery. Feature variants add typed participants and frozen evidence rather
than their own transaction/recovery protocols.

The filled-item release operation also has a narrow backward-compatible
recovery variant for already-migrated v2.16.1 artifacts. On exact item use it
can correlate one non-current capture-v1 history row with an initial imported
`UNLOADED/NONE` profile, recheck that evidence transactionally, and complete
through the ordinary receipt-first release boundary. It operates only on the
existing schema-1 target and never consults the legacy database, import
manifest, or transient `targetOrigin`.

The canonical lifecycle vocabulary is `ACTIVE`, `UNLOADED`, `CAPTURED`,
`COOP`, `ROSTER_STORED`, `PROVISIONED_DORMANT`, `DEAD_REVIVABLE`, `LOST`,
`RELEASED`, and `UNRESOLVED`. Command presentation, restoration, capture,
provisioning, roster, and coop code read that lifecycle; they do not maintain
separate status authorities.

## Dormant transitions require positive evidence

Tamework authors a dormant transition only when it has one of these exact
facts:

- a saved death event;
- an explicit destructive entity removal with `RemoveReason.REMOVE`; or
- terminal removal of a world configured as delete-on-remove, while the NPC's
  complete live state is still available.

Ordinary unload, temporary absence, and timeout are not death or Lost evidence.
Tamework does not infer a destructive lifecycle change just because an entity
is not currently loaded.

## Target and source files

The canonical write target is `tamework-state.sqlite` in Tamework's
universe-scoped data directory. If that target already exists, Tamework verifies
and opens it.

That existing-target precedence is deliberate: Tamework never merges later
changes from `tamework.sqlite` into an established replacement target. When
retesting migration from a recovered pre-upgrade database, stop the server and
back up the complete data directory first. Restore the complete pre-upgrade
directory, or move the existing `tamework-state.sqlite` target and its WAL/SHM
sidecars, `persistence-engine.json`, and prior `persistence-import-*.json` report
out of the active directory before starting the migration candidate. Restoring
only `tamework.sqlite` does not create a fresh migration. On the first successful
startup, `/tw debugdb status` reports target origin `IMPORTED_PUBLIC`; `EXISTING`
means a replacement target was reused and no import ran during that startup.

Do not test capture, filled-item release, or recovery until that status also
reports storage mode `READ_WRITE` and startup readiness `MUTATION_READY`. During
a direct import, `STARTING` with `RECONCILE_WORLD` running is expected while
Tamework seals world evidence. A click in that window is rejected with
`world_evidence_pending` and leaves the source item unchanged; retry the action
after mutation readiness is published.

When no replacement target exists, startup discovers at most one immutable
source across the current, legacy, and historical Tamework data directories:

| Source | Startup action |
| --- | --- |
| no source | Create an empty schema-v1 replacement target. |
| released SQLite schema v2, v3, or v4 | Import from a read-only consistent snapshot into a temporary schema-v1 target, verify it, then publish it atomically. |
| released five-file DAT bundle | Import the immutable bundle through the same verified target publication path. |
| unreleased development schema v5-v9 | Refuse startup without changing the source or creating a target. |
| malformed, split, or ambiguous sources | Refuse startup without guessing which source wins. |

The accepted SQLite compatibility boundary is the public v2-v4 lineage from
the last public release. The unreleased v5-v9 development lineage is
intentionally not migrated. Test worlds on those builds must restore a public
backup or start with a new world.

Source databases, DAT files, WAL files, and SHM files are never migrated in
place, renamed, moved, or deleted. A successful public import writes a
`persistence-import-<id>.json` report beside the replacement target.

## Process lock files

Tamework keeps its active process-ownership files in Hytale's backup-excluded
`LOCK` layout. `Tamework/Data/LOCK` protects the active persistence engine,
and `Tamework/Data/.tamework-import-lock/LOCK` serializes replacement-import
publication. These files are ephemeral: Tamework recreates them when needed,
and Hytale excludes them from world backups.

After an upgrade, `Tamework/Data/.tamework-persistence-engine.lock/` may
remain as an empty upgrade sentinel. It contains no gameplay data and
intentionally prevents older Tamework builds from reopening the upgraded
world.

## Public extension data

Integrations should use `ProfileDataApi` for namespaced data attached to a
canonical profile. The transactional extension adds versioned reads,
revision-fenced compare-and-set, stable idempotency keys, and operation lookup
after restart.

Do not write Tamework tables, internal metadata, or entity UUID aliases
directly.

## Data safety

- Stop the server before copying persistence data.
- Back up the complete Hytale world through the host or Hytale tooling.
- Tamework's database alone is not a complete world backup.
- Copy `tamework-state.sqlite` with its WAL/SHM sidecars and engine manifest
  when collecting a persistence support snapshot.
- Preserve any unreadable or refused source for diagnosis. Do not hand-edit
  profile identity, lifecycle, operation, or snapshot rows.

## Operator diagnostics

`/tw debugdb status`, `health`, and `integrity` print the same bounded
replacement status: engine lineage, storage mode, target origin, schema
version, startup state, operation counters, schema validation, and checkpoint
status.

`/tw debugdb detail` adds bounded feature, outbox, operation-phase, incident,
quarantine, and circuit counts. It does not repair data, retry an operation,
clear evidence, import coop residents, or change feature state.

`/tw debugdb export` writes a bounded redacted support ZIP under
`Data/diagnostics`. The bundle contains the same sanitized replacement status,
metrics, and durable detail exposed by the diagnostic reader. It excludes the
SQLite database, saves, player identities, coordinates, inventory payloads,
secrets, and unrestricted logs.
