---
name: tamework-persistence
description: Use when Tamework work touches saved companion state, the companion index, companion records or locations, owner or snapshot files under universe/Tamework/Companions, the generation fence, the write-behind writer, live-body saves and the change detector, capture, release, restore, store, coop, death or bonded flows, population admission, profile or extension data, the 3.x/4.x importer and legacy bodies or items, or settings and data-path stores.
---

# Tamework Persistence

Tamework 5.0 keeps companion state in one in-memory companion index, written
as JSON files under `universe/Tamework/Companions`. There is no database. A
live body's components are authoritative while it is in a world; the index
records where each companion is and holds a snapshot for companions that are
not in a world. ADR 0011 (`docs/decisions/0011-companion-index-persistence.md`)
is the decision record. Check the code before relying on any rule below.

## Find the Owner First

1. Resolve the source root (`src/main/java/com/alechilles/alecstamework`).
2. Read [authority-map.md](references/authority-map.md) and pick the package
   that owns the change.
3. Read [change-recipes.md](references/change-recipes.md) before changing a
   record field, a flow, a snapshot, admission, extension data or the importer.
4. Read [verification-matrix.md](references/verification-matrix.md) before
   adding tests or claiming completion.
5. For runtime symptoms, use `docs/agents/runtime-vs-source-checklist.md`.
   Lessons routed by `docs/agents/lessons-index.md` that describe SQLite,
   leases or projections are 4.x history; use them only for engine facts.

## Invariants

- **Commit the index before any live effect.** A flow that moves a companion
  between holders changes the record under the index lock, waits for
  `CompanionWriter.flushNow(owner)` to write the owner file, and only then
  removes a body, spawns one, or changes an item or coop slot. On a failed or
  timed-out flush it reverts the record and fails with a localized message.
- **Every holder change raises the generation**, and the new generation is
  committed before it is stamped on a holder. Spawns stamp
  `TameworkCompanionComponent{profileId, generation}` on the holder before
  `store.addEntity(holder, ref, AddReason.LOAD)`, then mark the body dirty.
  Never use `AddReason.SPAWN` for a respawn. Never lower a generation after a
  holder was stamped with the newer one.
- **One mutation lock, lock-free reads.** Writes go through
  `CompanionIndex.atomically`, `insert`, `update` or `revert`. Reads
  (`get`, `fileRecords`, `CompanionQueries`) are map lookups from any thread.
  Never wait on the writer while holding the index lock.
- **No file I/O on the world thread.** The writer thread
  (`tamework-companion-writer`) writes files; the reader thread
  (`tamework-companion-reader`) reads snapshot files. The world thread only
  serializes snapshots of bodies it owns and deserializes an already loaded
  snapshot at spawn. UI, HUD and list views read live components or the record
  `summary`, never a snapshot file.
- **Best-effort durability.** No operation log, outbox, receipts, retry ledger
  or exactly-once spends. A crash can lose a payment or a litter in the same
  second; restore from the index is the owner's safety net.
- **Time values.** World-time fields keep their sign and use `0` as unset;
  compare deadlines by ordering. Wall-clock fields (timers on the record,
  `updatedAtMs`) follow their own contracts. The importer copies old values as
  they are.
- **The body is the authority for its own state.** Component changes on a
  loaded body reach disk through Hytale's chunk save once the body is marked
  dirty (`CompanionChangeDetectorSystem` or `CompanionSaves.markChanged`).
  Do not copy that state into the record beyond the UI `summary`.

## Stop Conditions

Stop and resolve the design before editing when a proposal:

- adds a second persistence authority, a parallel store, or an embedded
  database (the SQLite driver exists only for the importer);
- stores state the live body already owns, or reads it back from the record
  while the body is loaded;
- changes a holder (body, item, coop slot, storage) without raising the
  generation, or runs a live effect before the commit is written;
- does file I/O, waits on a future or decodes a snapshot file on a world thread
  or in a UI or HUD refresh;
- renames or reuses a field name in `CompanionRecordBson` or `SnapshotEnvelope`,
  or changes a format without a decoder default;
- treats `UNLOAD` as death or loss, or removes a body the fence would accept;
- adds exactly-once, receipt, queue or reconciliation machinery for a rare
  crash window.

## Importer Lifetime

The 3.x/4.x importer is temporary: a later release removes it and the SQLite
driver, after which 3.x and 4.x worlds must run 5.0.x once. The lazy pieces
that handle old bodies, items, coops and players stay as long as imported
worlds exist. `companion/migrate/package-info.java` lists which is which; keep
the two groups apart.
