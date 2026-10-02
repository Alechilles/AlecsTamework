# ADR 0011: Companion Index Persistence

- Status: Accepted; implementation in progress on `refactor/persistence-rework`
- Date: 2026-09-30
- Supersedes: ADRs 0001, 0002, 0003, 0007, 0008 and 0010 when the rework ships (Tamework 5.0.0)
- Supersedes: ADR 0006 (public persistence import policy) for the 2.x path. 5.0 does not read
  2.x SQLite or DAT sources; those worlds run 4.3.x once first.

## Context

The replacement persistence runtime (about 89k lines in `persistence/`, two
SQLite databases, 39 tables) keeps a second copy of state that Hytale already
saves with each NPC. It then reconciles the two copies whenever the engine acts
on its own. Most persistence defects since July 2026 came from that
reconciliation. A live probe also showed that Hytale saves an NPC's component
changes only when the entity is marked dirty, and Tamework never marks it.

## Decision

1. Whoever physically holds a companion owns its state. A live body's codec
   components are authoritative, and Tamework marks bodies dirty when it
   changes them.
2. An in-memory companion index records where each companion is, and holds a
   snapshot for companions that are not in a world.
3. A per-companion generation number fences stale holders (bodies and capture
   items). The index wins conflicts, and the owner can always restore from it.
4. Storage is Hytale-style JSON through `StorageManager` and `BsonUtil`: one
   file per owner, one snapshot file per companion. There is no embedded
   database. A trimmed SQLite driver ships only as a temporary importer for
   3.x and 4.x saves.
5. Durability is best-effort, matching the engine. There is no operation phase
   machine, outbox, quarantine or compensation ledger.
6. Bonded companions merge into the same index. Public API becomes 3.0.0.

The full design, evidence and review record are in the external spec
`CodexDocs/alecstamework/persistence-rework/2026-09-30-persistence-rework-spec.md`.

## Consequences

- Persistence shrinks to an estimated under 10k production lines.
- The jar drops the SQLite native libraries after the import transition.
- Some flows (paid revival, capture-source spending) can lose an item spend in
  a crash in the same second. This is accepted.
- The 2.x import path is removed. Those saves get a localized
  "run 4.3.x first" notice.

## Migration

5.0 imports a 3.x or 4.x world by itself at the first start. The import only
reads the old databases, writes the new store all or nothing, and leaves a
report file and a receipt in `meta.json`. A world that cannot be converted can
start empty with `/tw persistence start-fresh`. A later release removes the
importer; after that, 3.x and 4.x worlds must run 5.0.x once. The operator
guide is the wiki page
[World Migration for Server Admins](../../wiki/Player-Guides/Troubleshooting-and-Glossary/World-Migration-for-Server-Admins.md).
