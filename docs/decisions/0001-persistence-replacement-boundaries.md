# ADR 0001: Persistence Replacement Boundaries

- Status: Accepted core boundaries; feature-scope decision partially superseded
- Date: 2026-07-23

## 2026-07-24 correction

ADR 0008 supersedes the product-scope portion of this decision. Tester-only
schema compatibility may still be discarded, but intended unreleased
capabilities must be rewritten on the replacement architecture rather than
removed.

Durable owner population/groups, command-family rosters, timed summoning,
companion provisioning, resolved capture-attempt consumption, tame-and-link
capture, paid revival, and captured-item-to-coop intake are required recovery
scope. Only profile-scoped virtual companion inventories and earlier bonded
vessel designs remain excluded.

At the 2026-07-24 scope correction, the then-reduced artifact was not a
feature-complete release candidate. ADR 0008 records the subsequent recovery;
the composed implementation now awaits exact-artifact live acceptance.

## Context

Tamework's unreleased persistence implementation grew from a public seven-table schema into
50 tables and many overlapping repositories, queues, recovery paths, and lifecycle authorities.
Because releases after June 30 were tester-only, preserving the unreleased v5-v9 lineage would
perpetuate complexity without protecting a public compatibility promise.

## Decision

The replacement persistence system follows these non-negotiable boundaries:

1. Use a fresh `tamework-state.sqlite` database whose schema begins at version 1.
2. Import public v2-v4 databases through a read-only, restartable importer.
3. Refuse unreleased v5-v9 databases without modifying them. Testers restore a public backup or
   create a new world.
4. Never dual-write the old and replacement databases.
5. Cut over complete feature slices; no feature may mix old and replacement persistence at
   runtime.
6. Keep domain contracts in `persistence.kernel`, `persistence.operation`,
   `persistence.projection`, and `persistence.control`. SQLite details stay behind
   `persistence.adapter.sqlite`.
7. Represent reads as `Found`, `Absent`, or `Failed`. Storage and decode failures are never
   successful absence.
8. Execute one logical operation in one explicit database transaction. Unrelated operations are
   not implicitly batched.
9. Treat zero as a valid revision and generation. For world timestamps, zero alone is unset and
   negative values are valid.
10. Remove the superseded runtime after all feature slices pass their replacement gates.
11. Do not preserve unreleased schema/API implementation compatibility, but
    recover still-required feature behavior through the shared replacement
    authorities.

## Corrected unreleased feature boundary

The July builds were tester-only, so their implementations and save formats do
not constrain the replacement. The following intended capabilities nevertheless
remain required:

- durable owner-population reservations/evidence and population-group assignment;
- command-family rosters and timed summon leases;
- companion provisioning, resolved capture attempts, and tame-and-command-link capture;
- paid revival and captured-item-to-coop intake;

Profile-scoped companion inventory remains deferred. Earlier bonded-vessel
designs remain removed.

Git history is a behavior/design record, not a compatibility promise or a
wholesale restoration target. Required features are recovered selectively
against the current public API and replacement schema without reviving the July
authority graph.

The completed cutover retains released behavior and restores the required
unreleased capabilities on shared authorities. Tamework now provides durable
owner/group admission, legacy and command-family links, timed storage,
provisioning, filled-spawner capture/release, direct live and captured-item
coop intake, free legacy and exact paid roster restoration, and namespaced
profile-extension data. None recreates one of the discarded authorities.

## Enforcement

- `PersistenceConsolidationInventoryGuardTest` makes legacy dependencies and size decrease-only.
- `PersistenceConsolidationInvariantManifestTest` requires named replacement evidence for every
  hard-won invariant.
- `LegacyPersistenceFixtureTest` fixes the accepted public input and refused development formats.
- The persistence replacement release checklist requires migration, crash, restart, architecture,
  and full-suite gates before release.

## Consequences

The refactor accepts a deliberate internal compatibility break for unreleased builds. In return,
Tamework gets one persistence lineage, one transaction model, explicit failure semantics, and a
bounded path for deleting rather than wrapping the accumulated runtime.
