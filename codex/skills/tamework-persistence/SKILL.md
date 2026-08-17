---
name: tamework-persistence
description: Use when Tamework work touches saved companion state, SQLite, tamework-state.sqlite, bonded-companions.sqlite, persistence operations, lifecycle, snapshots, projections or outbox delivery, recovery, incidents, quarantine, schemas, migration, import, or data-path stores.
---

# Tamework Persistence

Keep each change inside its owning persistence authority. Use current source,
accepted ADRs, and behavior tests as evidence; do not design from memory.

## Resolve the Authority First

1. Resolve the Tamework source root. Prefer the current workspace when it
   contains `src/main/java/com/alechilles/alecstamework`.
2. Read [authority-map.md](references/authority-map.md).
3. Classify the task as replacement persistence, bonded-companion persistence,
   public import, or a small settings/data-path store.
4. If ownership is unclear, trace the public API and data path before proposing
   a change. Do not bridge the replacement and bonded authorities by assumption.

## Select the Change Pattern

- Read [change-recipes.md](references/change-recipes.md) before planning or
  implementing a mutation, projection, schema, import, or recovery change.
- Read [verification-matrix.md](references/verification-matrix.md) before adding
  tests or claiming completion.
- For runtime symptoms, also read `docs/agents/runtime-vs-source-checklist.md`
  and the external `Persistence.md` lesson routed by
  `docs/agents/lessons-index.md`.
- For bonded-companion work, also read the external
  `2026-07-25-bonded-companion-lease-boundary.md` lesson routed by
  `docs/agents/lessons-index.md`.

## Preserve the Contract

1. Name the canonical authority, transaction owner, external live boundary,
   recovery evidence, publication path, public facade, and readiness owner.
2. Keep world, ECS, inventory, filesystem, network, and projection callbacks
   outside database transactions.
3. Preserve stable IDs, versioned payloads, idempotency, revision fences, exact
   unknown-commit readback, negative timestamps, and zero-valued revisions.
4. Extend an existing operation family when it owns the behavior. Do not add a
   feature-local writer, phase graph, retry queue, journal, or lifecycle copy.
5. Test an observable failure or recovery outcome. Do not add source-shape,
   file-presence, or raw-schema-text tests.

## Stop Conditions

Stop and resolve the design before editing when a proposal:

- writes canonical tables outside the owning store or facade;
- mixes generic replacement and bonded-companion records;
- retries an unknown commit without exact evidence;
- treats unload or absence as positive death evidence;
- changes a schema, stable ID, payload, or public import boundary without an
  explicit compatibility decision.
