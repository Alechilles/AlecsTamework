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
- configured-coop capture and release of live NPCs;
- saved death and Lost restoration, without payment; and
- namespaced profile extension data.

There is one capture path, one filled-item release path, one direct-live coop
path, and one restoration path. Captured items do not enter coops.

The canonical lifecycle vocabulary is `ACTIVE`, `UNLOADED`, `CAPTURED`,
`COOP`, `DEAD_REVIVABLE`, `LOST`, `RELEASED`, and `UNRESOLVED`. Command
presentation, restoration, capture, and coop code read that lifecycle; they do
not maintain separate death, lost, captured, or coop status authorities.

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
