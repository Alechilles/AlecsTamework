---
title: "Companion Store and Data Paths"
order: 1
published: true
draft: false
---
# Companion Store and Data Paths

Parent: [Data and Persistence](/mod/alecs-tamework/data-and-persistence) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

Tamework 5.0 keeps every companion in an in-memory index and saves it as plain
JSON files under the world's `universe` folder. There is no database. This page
describes the folder, how writes reach disk, how stale bodies and items are
refused, and what operators can check. The design record is ADR 0011
(`docs/decisions/0011-companion-index-persistence.md` in the repository).

Tamework 3.x and 4.x used SQLite databases (`tamework-state.sqlite` and
`bonded-companions.sqlite`). 5.0 imports them once at the first start and never
writes them. See
[World Migration for Server Admins](/mod/alecs-tamework/world-migration-for-server-admins)
for the import, the report file, `/tw persistence start-fresh` and 2.x worlds.

## Folder layout

All companion data lives in `universe/Tamework/Companions`
(`CompanionStorage.root`, resolved from the server's universe path):

```text
universe/Tamework/Companions/
  meta.json                   format version, created-by version, import or fresh-start receipt
  owners/<ownerUuid>.json     every record owned by that player
  owners/_unowned.json        records with no owner (rare)
  snapshots/<profileId>.json  latest saved body of one companion
  legacy-aliases.json         old 3.x/4.x body UUIDs to profile IDs (written once by the import)
  locate-progress.json        where the post-import locate pass stopped (only while it has work)
```

- **`meta.json`** marks the store as created. While it exists, Tamework never
  imports old data again. An imported store has an `Import` section; a store made
  by `/tw persistence start-fresh` has a `FreshStart` section.
- **Owner files** (`CompanionStore`) hold `Format`, `Owner`, `Version` and three
  arrays: `WorldBound`, `Portable` and `Unreadable`. Each record entry is written
  by `CompanionRecordBson` and holds the profile ID, revision, generation, owner,
  role, display name, location, last known body UUID, presentation summary,
  roster and timer fields, provider claims, linked tool IDs and extension data.
  An owner file is rewritten whole when any of its records changes, and deleted
  when the owner has nothing left.
- **Snapshot files** (`SnapshotEnvelope`) hold `Format`, `Generation` and `Data`.
  Format 1 is the full serialized entity. Format 0 is a companion state imported
  from 3.x or 4.x; it becomes format 1 the first time the companion is restored.
  Only the latest snapshot is kept. Releasing a companion deletes it.
- **`legacy-aliases.json`** lets old bodies in the world be matched to their
  imported records. Never delete it. If `meta.json` says the import wrote aliases
  and the file is missing or empty, the store refuses to load.

A released companion keeps a small `RELEASED` record (a tombstone) so an old body
that loads later is removed instead of being adopted again. Tombstones do not
count toward any limit.

## Other Tamework files

These files are not part of the companion store:

- `universe/Tamework/Settings`: `tamework-settings.json` (`/tw settings`,
  `TameworkSettingsStore`), `tamework-settings-announcement.json` and
  `tamework-settings-announcement-state.json`.
- `universe/Tamework/Data` (`TameworkDataPathService`):
  `animal-progression-clock.json`, the advisory
  `cache/captured-item-locations.json`, and the import reports
  (`import-report-<UTC time>.txt` and `import-report-failed.txt`). The old 3.x
  and 4.x databases also sit here and are left unchanged.

When Tamework cannot find the server's runtime root, the `Data` folder falls
back to the plugin's own data folder. The companion store never does: it always
uses the universe path.

## Locations

Each record has one location kind (`LocationKind`). The panel, the API and the
limits read it; nothing else decides where a companion is.

| Kind | Holder |
| --- | --- |
| `LIVE` | A body in a world, loaded or not. |
| `ITEM` | A filled capture item. The item carries only the profile ID, the generation and a few presentation keys. |
| `COOP` | A coop block slot. |
| `STORED` | Nobody: a roster, bonded storage, timed-summon storage or a provisioned companion. |
| `DEAD` | Nobody. Revivable from its snapshot. |
| `LOST` | Nobody. Recoverable from its snapshot. Causes include a removal without death, a deleted portal or instance world, a destroyed capture item, and an imported companion whose body was not found. |
| `RELEASED` | Tombstone. |

Whether a `LIVE` body is loaded is runtime state only (`LoadedBodies`).

## Writes: write-behind and flush

One writer thread, `tamework-companion-writer` (`CompanionWriter`), does all
file writes. The world thread never reads or writes companion files.

- A record change marks its owner file dirty. A new snapshot is queued for its
  companion.
- The writer flushes every 250 ms, or at once when a flow asks for it. Each flush
  writes queued snapshots first, then the dirty owner files, then pending
  snapshot deletes. A snapshot is deleted only after the owner file that pointed
  at it no longer needs it.
- When a record moves to another owner, the new owner's file is written before
  the old owner's file. If a crash leaves the same profile in two files, the load
  keeps the higher revision.
- Every file goes through Hytale's `StorageManager` (`HytaleCompanionFileIo`), so
  each write is atomic, keeps a `.bak` copy, and waits while a Hytale backup
  runs. A file whose main copy cannot be parsed is read from its `.bak` copy, with
  one WARN per file.
- A failed write keeps its work pending and retries with backoff from 1 second up
  to 30 seconds. Nothing unwritten is dropped. Memory stays authoritative for the
  running server, and the failure shows in diagnostics.

**Flows that commit before a live effect.** Capture, release from an item, store,
summon, recall restore, revive, coop intake and coop release change the record in
memory, then ask the writer to flush that owner's file and wait up to 5 seconds.
Only after the write completes, and only if the record has not changed since,
does the flow remove or spawn a body, or consume an item. On a failure or timeout
the flow undoes the record change and shows the player a message. An unwritten
state therefore never drives a removal or a spawn.

**Background changes** (stats, needs, progression, names, positions) reach disk
through the change detector (`CompanionChangeDetectorSystem`). It checks loaded
companions every 2 seconds for discrete changes (owner, name, links, traits,
talents, attachments, alarms, levels, life stage, breeding settings) and every
60 seconds for slowly drifting values (needs, happiness, XP, timers). A change
marks the body dirty so Hytale saves its chunk. New flow code that needs an
immediate save calls `CompanionSaves.markChanged`.

**Snapshots** are taken on the world thread whenever a companion leaves a world
(capture, coop intake, store, death, loss, removal of a delete-on-remove world).
On a chunk unload a snapshot is taken only if the last one is at least 5 minutes
old; the presentation summary is refreshed on every unload. If a snapshot cannot
be taken, capture, coop intake and store stop with a message and change nothing;
death, loss and unload keep the previous snapshot and log a WARN.

**Shutdown.** A `ShutdownEvent` handler at priority -28 (after worlds shut down
and before universe resources flush) runs a final flush with a 10 second
deadline. It writes only what the index already holds and reads no ECS state.
On a crash, up to about 2 seconds of discrete changes and 60 seconds of drift can
be lost.

**Startup.** Every owner file is read into memory before any world starts.
Snapshots are read on demand on the `tamework-companion-reader` thread and are
not cached.

## Generation fence

Every companion body carries `TameworkCompanionComponent` with its profile ID and
generation. Every capture item carries the same pair. The record's generation
goes up by one each time the holder changes (capture, release, store, summon,
restore, coop intake and release, death, loss). The stamp is committed before the
new holder exists. `CompanionFence` then decides what to do with a body as it is
added to a world:

| Situation | Result |
| --- | --- |
| The record is `LIVE`, the generations match, and no other body of this companion is loaded | Accepted and registered as the loaded body. |
| The body's generation is newer than the record | Accepted; the record's generation is raised and a WARN is logged. An older loaded body is removed. |
| Another body of this companion is already loaded | The newcomer is removed. |
| The record is not `LIVE`, or the body's generation is older | Removed. |
| The record is `RELEASED` | Removed. |
| No record, but the profile ID was in a record that could not be read at startup | Left alone. |
| No record at all, and the body is owned and tamed | Adopted as a new `LIVE` record (covers a tame that had not been written before a crash). |
| No record at all otherwise | Removed. |

Fence removals are saved, drop nothing and do not count as a death or a loss.
The removal callback acts only on the registered body, so it ignores bodies the
fence or a flow removed.

The same rule applies elsewhere:

- **Capture items.** An item releases its companion only when the record is
  `ITEM` and the generations match. Any other copy becomes an empty capture item
  with a message. A duplicated item can release its companion only once.
- **Coop slots.** A slot entry counts only while the record is `COOP` at that
  block and slot with the same generation.

Bodies from 3.x and 4.x have no stamp. Those are matched through
`legacy-aliases.json` as their chunks load; see the migration page.

## Unreadable files and records

- **Owner file that cannot be parsed** (both the main file and `.bak`), or that
  uses a newer format: it is renamed to `<name>.json.unreadable-<timestamp>` so it
  is never overwritten, a WARN reports the count, and that owner starts empty. An
  admin can restore the file from a backup while the server is stopped.
- **One record that cannot be decoded** inside a readable file: its raw entry is
  kept in the file's `Unreadable` array and written back unchanged on every
  rewrite. Its profile ID is added to the unreadable set, so the fence never
  adopts or removes its body. `unreadableRecords` in diagnostics counts these.
- **A file that cannot be read at all** (an I/O error, not a parse error), or an
  unreadable `legacy-aliases.json`: the store does not load. Companion saving
  stays off for the whole server, a SEVERE log line names the cause, and nothing
  is written. Admins who join see a notice. Fix the cause and restart.
- **Snapshot that cannot be read**: the flow that needed it fails with a message
  and changes nothing.

## Extension data

`ProfileDataApi` stores namespaced values on the companion record itself, in its
`Extensions` map (`namespace/key` to a revision and a JSON string). The values
travel with the record through every location and owner change, and are deleted
when the companion is released.

- `put` and `delete` return at once; the next flush writes the owner file.
- `compareAndSet` completes only after the owner file is written and undoes its
  change if that write fails.
- Large values slow down every rewrite of that owner's file. Keep them small.

See [Profile Data API Reference](/mod/alecs-tamework/profile-data-api-reference).
Do not edit owner or snapshot files from another mod; use the public API.

## Backups

- Hytale's own backups zip the `universe` folder, so they include
  `Tamework/Companions`. Because every write goes through `StorageManager`, a
  backup never holds a half-written file.
- Back up and restore the whole `universe` folder together. Restoring only the
  `Companions` folder, or only the world chunks, pairs records with bodies or
  items from a different time. The generation fence then removes bodies it sees
  as stale.
- Stop the server before copying files by hand.
- Do not move companion data into the plugin's data folder. Hytale backups do
  not include it, and a folder-installed mod is replaced on update.

## Diagnostics

There is no persistence debug command in 5.0. Use the server log and
`TameworkApi.diagnostics().getPersistenceDiagnostics()`
([Diagnostics API Reference](/mod/alecs-tamework/diagnostics-api-reference)):

| Field | Meaning |
| --- | --- |
| `databasePath` | The `Companions` folder. |
| `totalBytes` | The folder's size, measured off the world thread and up to about 30 seconds old. |
| `recordsByLocation` | Records per location kind, including `RELEASED`. |
| `lastFlushAtMs` | Wall-clock time of the last successful flush. |
| `lastFailure` | The writer's current failure, or null while writes succeed. |
| `unreadableRecords` | Records that could not be decoded at load. |
| `queueMetrics.queueDepth` | Owner files and snapshots waiting to be written. |
| `health.status` | `HEALTHY` while writes succeed, `DEGRADED` while the writer has a failure. |

Useful log lines:

- `Companion store loaded with <n> quarantined files and <n> unreadable records`
  (WARN): see "Unreadable files and records" above.
- `Companion store at <path> could not be read; companion persistence is disabled`
  (SEVERE): the store did not load.
- `Companion persistence is disabled until the ... companion data on this world is
  imported` (WARN): a failed import; read the import report.

For a support request, send the server log, the import report if the update
failed, and a copy of the `Companions` folder taken while the server is stopped.

## Related Pages
- [World Migration for Server Admins](/mod/alecs-tamework/world-migration-for-server-admins)
- [Diagnostics API Reference](/mod/alecs-tamework/diagnostics-api-reference)
- [Profile Data API Reference](/mod/alecs-tamework/profile-data-api-reference)
- [Architecture Overview](/mod/alecs-tamework/architecture-overview)
