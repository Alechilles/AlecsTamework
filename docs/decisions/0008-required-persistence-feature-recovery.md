# ADR 0008: Required Persistence Feature Recovery

- Status: Accepted; automated implementation verified, live verification pending
- Date: 2026-07-24

## Context

ADR 0001 correctly allowed Tamework to reject tester-only persistence schemas
and rewrite unreleased implementations. It incorrectly converted that freedom
into removal of intended product capabilities.

HyDragon's current design requires durable population groups, provisioning,
command-family rosters, resolved capture-attempt consumption, in-place
tame-and-link capture, timed summoning, and paid command revival. Tamework also
still requires captured-item-to-coop intake. None has shipped publicly, but all
remain intended behavior.

The deleted July runtime is not an acceptable restoration target. It duplicated
canonical lifecycle, operation phases, repositories, recovery loops, readiness,
and projections. The replacement core was created specifically so these
features could share those mechanisms.

## Decision

1. Restore the required feature behavior on the current replacement core.
2. Treat Git parent `21e01904` as characterization evidence and a selective code
   donor, never as a tree to revert wholesale.
3. Keep one canonical profile/lifecycle, shared operation phase graph, writer,
   projection outbox, recovery registry, descriptor DAG, containment model, and
   shutdown protocol.
4. Add only focused feature detail and shared-operation participants.
5. Keep public `v2.16.1` import support and non-destructive v5-v9 refusal.
6. Do not migrate the current tester-only replacement schema. Amend fresh
   schema v1 in place and require testers to restore a public backup or use a
   new world.
7. Block public release until the required Tamework and HyDragon acceptance
   matrices pass.

Required capabilities are:

- durable owner-population admission and reconciliation;
- population-group classification and admission;
- command-family rosters;
- per-profile timed summon/storage leases;
- idempotent companion provisioning and activation;
- durable terminal capture rolls with configured exactly-once source spend;
- in-place tame/owner/role/profile/roster/group/lease capture;
- data-driven exact multi-item paid revival;
- captured-item-to-coop intake through the normalized coop operation.

The only intentionally excluded designs are:

- profile-scoped virtual companion inventories, which remain deferred;
- bonded vessel/item-state designs, which remain removed.

## Architecture ceilings

Without a later approved ADR, the recovered design may not exceed:

- 13 feature descriptors;
- 20 operation kinds;
- 29 schema tables.

These values match the last converted replacement checkpoint and are ceilings,
not targets. Resolved capture and tame/link must use the capture operation.
Captured-item intake must use the coop-capture operation. Paid revival adds no
feature-specific table.

Feature-specific operation phases, journals, recovery scanners, readiness
graphs, projection journals, transaction runners, and copied lifecycle/owner
state remain prohibited.

## Consequences

- The prior reduced artifact remains useful for migration-core evidence but is
  not a feature-complete release candidate.
- The earlier line-count reduction must be reported as partly caused by a scope
  error, not wholly as architectural simplification.
- Production code will grow as required behavior returns, but normalized
  complexity should remain substantially below the old implementation because
  cross-cutting machinery stays shared.
- Recovery proceeds in gated vertical slices described in
  `2026-07-24-required-persistence-feature-recovery-plan.md`.

## Implementation record

The recovered cutover was completed in `f7c3f046` on
`refactor/persistence-consolidation`. It restores every required capability
through the replacement runtime without reinstating the deleted persistence
lineage or any feature-specific writer, phase graph, recovery scanner,
readiness graph, or projection journal.

The implementation remains at the agreed ceilings:

- 13 feature descriptors;
- 20 operation kinds;
- 29 schema tables.

The final automated gate ran 3,001 Tamework tests with zero failures, zero
errors, and one intentional skip. HyDragon then passed its complete
131-test `verify` run against the exact Tamework jar, including validation of
366 JSON assets and five locales. Architecture, crash-recovery, schema,
ECS/thread-safety, API-boundary, and stale-runtime guards all passed.

The tested Tamework artifact was:

- file: `target/Alec's Tamework! v3.0.0.jar`;
- SHA-256:
  `ccc7b5f25f0ab0bdc06d5fb2dc915ea096a4103ae32f97fe9fcb5061b81e7a24`;
- schema-v1 SHA-256:
  `8ad3e6a9783b3fb4f85cf91f14546fd401651249239554ec7f3be76e2b2bf0d5`.

The complete authority, operation, schema, and complexity inventory is in
`docs/Required-Persistence-Feature-Inventory.md`. Live Tamework and HyDragon
acceptance rehearsals remain required before release preparation may begin.
