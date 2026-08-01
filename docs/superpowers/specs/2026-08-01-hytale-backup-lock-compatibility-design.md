# Hytale Backup Lock Compatibility Design

## Problem

Tamework stores its persistence engine lease inside the Hytale universe at
`Tamework/Data/.tamework-persistence-engine.lock`. The lease holds a Java
`FileLock` for the server lifetime.

Hytale 0.5.7 backs up the universe by walking every regular file, reading each
file to calculate its CRC, and then writing it to the backup ZIP. Hytale skips
only paths whose final path element is exactly `LOCK`. On Windows, reading
Tamework's held lease file fails with `ERROR_LOCK_VIOLATION`, surfaced by Java
as:

```text
The process cannot access the file because another process has locked a
portion of the file
```

The same incompatibility can occur while the short-lived
`.tamework-import.lock` is held during persistence import publication.

## Scope

This change fixes the proven lock-file incompatibility with Hytale backups.
It does not redesign Tamework's SQLite operation scheduling or claim a new
transactional contract between Hytale backups and every Tamework SQLite read
or write.

The implementation must preserve:

- one persistence engine owner per data directory;
- refusal of concurrent legacy and replacement engines;
- import publication serialization;
- clean and unclean engine-manifest shutdown semantics;
- support for worlds containing the former regular-file engine lock.

## Base-Game Contract

The implementation targets Hytale Shared Source version `0.5.7`:

- `com.hypixel.hytale.server.core.util.backup.BackupUtil#walkFileTreeAndZip`
  skips a regular file only when `path.endsWith("LOCK")`;
- `com.hypixel.hytale.server.core.universe.Universe#runBackup` waits for
  base-game storage operations and world saves before starting `BackupTask`;
- Hytale exposes no pre-backup plugin event in this version.

Tamework lock paths therefore need to use a final path element equal to
`LOCK`, not merely a filename with a `.lock` suffix.

## Design

### Engine lease layout

The active engine lease moves to:

```text
<Tamework data directory>/LOCK
```

`PersistenceEngineLease` continues to open one writable `FileChannel`, acquire
one process-level `FileLock`, and hold both until clean or unclean shutdown.
Only the path changes; ownership and lifecycle behavior remain unchanged.

### Legacy engine-lock sentinel

The former path remains reserved:

```text
<Tamework data directory>/.tamework-persistence-engine.lock/
```

It becomes an empty directory sentinel. An older Tamework build expects that
path to be a writable regular file, so it fails closed instead of starting
alongside the new engine lease.

On first startup of the new implementation:

1. Create the Tamework data directory.
2. If the legacy path is a regular file, open it and acquire its old-style
   process lock.
3. If that lock is unavailable, fail with
   `persistence_engine_lock_unavailable`; another legacy owner may be active.
4. While retaining the exclusive legacy `FileLock`, revalidate the legacy path
   with `NOFOLLOW_LINKS`, delete the former regular file, and create the empty
   directory sentinel at the same path. Release the lock and close the handle
   only after the sentinel has been created.
5. If the legacy path is already a directory, retain it.
6. Refuse other path types rather than replacing an unknown filesystem entry.
7. Open and acquire the active `<data>/LOCK` lease.

The file-to-directory transition is deliberately performed while the legacy
lock remains held, which preserves fail-closed behavior for cooperating
Tamework processes during the supported stopped-server upgrade flow. Portable
Java NIO cannot make a file-to-directory pathname replacement atomic against
hostile external namespace mutation. That threat model is unsupported: this
compatibility behavior covers Hytale's walks and reads plus cooperating
Tamework processes, not external actors mutating the legacy path during
migration.

### Import admission layout

The import admission lock moves to:

```text
<Tamework data directory>/.tamework-import-lock/LOCK
```

The parent directory is created before opening the file. Existing
`.tamework-import.lock` regular files are inert artifacts left by previous
builds; they are no longer locked and remain safe for Hytale to read. Import
serialization still uses `FileChannel.tryLock()` and retains the current
failure code, `persistence_import_lock_unavailable`.

### Failure handling

- An active legacy engine lock aborts acquisition without modifying it.
- A non-file, non-directory legacy path aborts acquisition without replacing
  it.
- Failure to create either backup-compatible lock layout closes any acquired
  channel and preserves the existing primary diagnostic.
- Close remains idempotent and continues to release the `FileLock` before the
  channel.

## Testing

Implementation follows a red-green cycle.

`PersistenceEngineLeaseTest` will first add failing coverage that:

- acquires a lease and applies Hytale 0.5.7's regular-file filter;
- proves the held active lock is excluded because its final element is
  exactly `LOCK`;
- upgrades a former regular-file engine lock into a directory sentinel;
- refuses migration while the former lock is held;
- refuses unknown filesystem entry types without replacing them.

`PersistenceEngineLeaseProcessTest` continues proving cross-process exclusion
for both engine lineages after the path change.

`PublicPersistenceImporterTest` will first add failing coverage that:

- proves the held import lock is excluded by Hytale's filter;
- preserves concurrent import refusal;
- uses the dedicated import-lock directory.

After focused tests pass, the complete `./mvnw test` suite and the repository's
persistence architecture/crash-matrix checks will run. A Windows-specific
manual reproduction will also acquire the resulting locks and read every file
selected by Hytale's filter; the selected-file reads must complete without the
previous lock-violation exception.

## Documentation and Release Notes

`CHANGELOG.md` will receive a player-facing fix entry stating that automatic
Hytale world backups no longer fail on Windows because of Tamework persistence
locks.

The persistence data-path documentation will describe `LOCK` as an ephemeral,
Hytale-excluded ownership file and state that the legacy engine-lock directory
is an upgrade sentinel, not persisted gameplay data.

No version bump, packaged-runtime deployment, or release publication is part
of this change.
