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

## Entity checkpoint history retention

Internal entity checkpoints retain current canonical state and small permanent
idempotency records. Superseded checkpoints published at least one hour ago can
discard their large operation payload and consumed outbox event after the
extension index acknowledges them. Incomplete operations, active quarantine,
public extension data, and other operation families keep their evidence.

Cleanup runs in bounded batches as new checkpoints publish. To reclaim existing
file space, an administrator can run `/tw debug persistence compact` on the running
server. This temporarily pauses Tamework saves and companion mutations, drains
accepted work, removes eligible history, and rebuilds the database. Automatic
profile snapshots and unload checkpoints wait in their existing save coordinators
and resume afterward. Completion
reports the before/after disk usage. Allow several minutes for large databases and
up to twice the database size in additional free disk space. Keep a current backup.

The rebuild image is created beside `tamework-state.sqlite`, on the volume whose
free space is checked. Tamework validates that copy and copies it back through
SQLite's transactional backup API, then removes the temporary image. It does not
change the server's global SQLite temporary-directory setting or replace database
files underneath open connections. The large rebuild image therefore does not
depend on a hosting container's separate system temporary-storage allowance.

If work cannot drain, unfinished operations remain, or a reader blocks the final
WAL checkpoint, the command reports failure in the server log; retry after the
cause clears. Database integrity failures keep mutations blocked. Avoid stopping
the server during maintenance.

The command also enables incremental vacuum. New databases already enable it, so
later checkpoint cleanup returns free pages in small batches. Small retry records
still accumulate; this reduces growth rather than imposing a fixed size cap.


### Checking compaction in game

Use a disposable copy of a world with a backed-up database. Keep its player
inventories, world data, and Tamework database from the same save: filled capture
items require their matching persisted companion records. Do not replace only the
database with an empty fixture when checking companion behavior. After the world has
loaded, run `/tw debug persistence compact` and check the completion message's
before/after MiB. A database with few free pages may shrink very little, and zero
compacted operations is valid when no checkpoint history is eligible. Afterward,
use a companion and reopen the test world to check that ordinary saves still work.
Retain the server log if maintenance fails.

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

Startup recovery retires a `PREPARED` dormant transition as `FAILED` when its
preparation validation reports `operation_prepared_detail_missing`. Later
companion changes, such as a coop capture and release, can invalidate that
operation's frozen source revision or alias. This database-only operation has
no intermediate live effect or preparation reservation to undo. Recovery keeps
the current lifecycle, aliases, snapshots, and outbox unchanged and continues
with unrelated operations. `EXPLICIT_RECALL_EXHAUSTED` retains its existing
failure handling because its snapshot validation can also reject unreadable
evidence. Other operation kinds, phases, and failure causes retain their
existing failure handling.

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
startup, `/tw debug persistence status` reports target origin `IMPORTED_PUBLIC`; `EXISTING`
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

At startup, Tamework waits up to three seconds for a previous server process
to release its persistence lock. If the lock remains held, startup fails
closed. The error reports `path=active` or `path=legacy` and
`scope=same_process` or `scope=external_process`. For `external_process`,
close the other Hytale server process and restart. For `same_process`, close
the Hytale client or server process fully and then restart it. Do not delete or
replace a held lock file.

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

Managed-coop production waits while a resident's slot has an unfinished capture
or release operation. If persistence rejects a production checkpoint before
submission, production for that animal pauses until the Tamework runtime restarts;
the rejection is logged once for that animal. Restarting does not clear durable
quarantine. An uncertain coop release still needs exact entity/receipt evidence
before it can be resolved safely.

An uncertain new single-animal population admission keeps its capacity reserved
and blocks its exact operation and profile. It does not block unrelated animals
owned by the same player. At startup, matching older owner-wide admission locks
are narrowed only after the saved reservation and remaining locks are verified.
The incident stays open; this repair does not assume the animal was created or
cancel the reservation. Incomplete evidence remains protected.

`/tw debug persistence status` and `health` print the same bounded
replacement status: engine lineage, storage mode, target origin, schema
version, startup state, operation counters, schema validation, and checkpoint
status.

`/tw debug persistence detail` adds bounded feature, outbox, operation-phase, incident,
quarantine, and circuit counts. It does not repair data, retry an operation,
clear evidence, import coop residents, or change feature state.

`/tw debug persistence export` writes a bounded redacted support ZIP under
`Data/diagnostics`. The bundle contains the same sanitized replacement status,
metrics, and durable detail exposed by the diagnostic reader. It excludes the
SQLite database, saves, player identities, coordinates, inventory payloads,
secrets, and unrestricted logs. All persistence diagnostic response lines also go to the
server log so operators can collect them after command chat closes.

### Automatic failure evidence

Starting with 4.1.3, automatic failure bundles also include `failure-records.json`.
The shared SQLite writer and reader collect a bounded diagnostic snapshot at the
failure boundary, before rollback or connection close. The reporter receives
immutable evidence, so startup shutdown cannot make it disappear before upload.
Submission still follows the existing Beacon reporting settings.

Write evidence targets the failing operation. Read failures can include a bounded
sample of unfinished operations; the sample is context, not proof that those
operations caused the read failure. Recovery failures outside a database callback
can still include the claimed operation's saved metadata. Reports include safe
operation, participant, lifecycle, alias, snapshot, and related-record metadata
when available. They preserve revisions and relationships needed to compare
expected state with saved state.

Record identifiers use consistent pseudonyms within each capture. Automatic
evidence excludes raw identities, names, coordinates, full snapshot and inventory
payloads, arbitrary extension data, and unrestricted exception messages. Nested
causes include exception classes, source frame line numbers, recognized machine
error codes, and SQLite numeric error codes. Different underlying failures are
grouped separately even when their outer classification is `sqlite_unknown`.

Collection has row, byte, and execution bounds. Partial, unavailable, skipped,
and truncated evidence is marked explicitly; a missing section does not prove
that the database contained no records. Transaction-local evidence may include
uncommitted changes and is not proof of a successful commit. Capture does not
repair records, retry operations, or change persistence failure handling. If
the connection cannot open, a minimal report still includes the failure evidence
available without database access. Bonded-companion diagnostics retain their
separate aggregate view and authority.

Each database capture uses at most three sampled operations, four related
profiles, and twelve rows per section, with a 150 ms SQLite work budget and a
96 KiB serialized evidence limit. Busy, timeout, and corrupt-database failures
skip further database queries. A bundle can retain up to four captures and is
limited to 512 KiB compressed. `collection-limits.json` identifies members
dropped to fit; record evidence takes priority over aggregate detail.
