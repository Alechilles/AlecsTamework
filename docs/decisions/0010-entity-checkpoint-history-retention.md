# ADR 0010: Entity checkpoint history retention

- Status: Accepted
- Date: 2026-09-16

## Problem

A reported 4.0 database contained 644 companion profiles and 166,484 internal
entity checkpoint operations. Their operation and outbox payloads held about
2.79 GB of JSON, while current canonical checkpoints held only 3.7 MB.

## Decision

Allow compaction only for `profile_extension_mutation` PUT operations in
`Alechilles:Tamework:EntityCheckpoint` whose idempotency keys begin with
`companion-entity-checkpoint:v1:`. This is a narrow exception to ADR 0007's
outbox retention rule. Public extension operations and other operation families
keep their existing evidence and replay behavior.

An eligible operation must be `PUBLISHED` for at least one hour, have exactly
one outbox event acknowledged by `profile_extension_index`, and have a newer
canonical extension revision. Active quarantine prevents compaction. The
outbox's highest sequence is retained to preserve its head. Signed timestamps
are compared by ordering; canonical revision, not timestamp, proves supersession.

Compaction replaces the operation payload with a versioned receipt containing
the exact original payload hash, and removes its outbox event in the same
transaction. Operation identity, idempotency key, timestamps, and participants
remain. An exact resubmission returns `PUBLISHED` with no historical events;
different request bytes still conflict. An old checkpoint cannot execute again
merely because its history was compacted. Canonical checkpoint data is unchanged.

The shared operation engine submits maintenance to the existing single writer
after successful publication. Every 16 internal checkpoint publications examine
at most 64 extension events, using a wrapping sequence cursor. There is no
polling timer, entity scan, new executor, schema, or persistence authority.
Maintenance is a separate transaction; its failure does not undo publication.
Unknown commit readback checks the exact receipts and removed events. Accepted
maintenance drains with the existing writer at shutdown.

## Rebuild and compatibility evidence

`ExactCheckpointCompanionRecallRecovery` reads checkpoint extensions from
canonical storage. `SqliteDetailProjectionBootstrap` rebuilds
`ProfileExtensionProjectionIndex` from canonical extension rows. The extension
feature requires only this index, so consumed superseded checkpoint events are
not necessary for startup or recovery. Behavior tests compare canonical rebuild
with the pre-compaction projection and preserve incomplete/unacknowledged work.

No schema version changes. Receipts are internal terminal-operation payloads;
they are never passed to unfinished-operation recovery. Older builds can read
canonical state but cannot replay compacted checkpoint requests successfully.

## Space reclamation

Administrators can run `/tw debug persistence compact` while the server stays
online. The runtime pauses new persistence mutations, drains accepted workflows,
and reserves shutdown ownership before queueing maintenance on the existing
single writer. Automatic profile snapshots and entity checkpoints wait on the
maintenance completion inside their existing bounded coordinators, retaining
one-off unload captures. Deferral starts before reads and authoring; a failed save
that crosses a maintenance generation restarts its reads after maintenance to
avoid stale revision fences. No new worker or durable queue is introduced. Pending durable operations prevent the rebuild. A drain timeout
or maintenance failure is reported; ordinary admission resumes unless shutdown
or a database integrity failure prevents it.

Maintenance compacts eligible history in committed batches, checks temporary disk
headroom, enables incremental auto-vacuum, rebuilds with `VACUUM`, and truncates
the WAL. A busy reader can prevent the final checkpoint and cause a retryable
command failure. Rebuilding a large database may take several minutes and requires
up to twice the original database size in additional free space. Tamework saves
and companion mutations are unavailable during maintenance; the game server keeps
running. Maintenance is explicit and never starts automatically at shutdown.

New databases enable incremental auto-vacuum before schema creation. Converted
and new databases reclaim up to 512 free pages per eligible history cleanup batch.
WAL checkpointing makes those reductions visible on disk. Small idempotency records
remain, so growth is reduced rather than capped at a fixed file size.
