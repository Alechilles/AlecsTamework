# Persistence Feature Recovery Donor Manifest

Status: characterization inventory; update at every feature gate
Historical donor checkpoint: `21e01904`
Current recovery plan: `2026-07-24-required-persistence-feature-recovery-plan.md`

## Rules

- Historical code is evidence, not a wholesale restoration target.
- Current replacement-core fixes and current HyDragon product decisions win.
- Restore behavior tests by outcome, not assertions about deleted class names.
- Production dependencies on the superseded `persistence.sqlite` runtime remain
  forbidden.
- No disabled duplicate old/new production implementation is retained.

## Feature inventory

| Feature | Historical evidence to reuse selectively | Must be rewritten/adapted | Must remain deleted |
| --- | --- | --- | --- |
| Owner population | ADR 0008, reservation/evidence codecs, concurrency/crash/sealed-absence tests | adapters against current transaction context; participation in every current lifecycle path | old population lifecycle rows, private phases, recovery service, mutable committed index authority |
| Population groups | ADR 0009, deterministic policy/planners/codecs, normalized schema/tests | current config wiring, lifecycle joins, API/capability, current operation participants | classification status machine, count-evidence authority, receipt/recovery tables |
| Command rosters | ADR 0010, membership/slot domain, projections, operation tests | current command UI/item orchestration and profile/lifecycle joins | copied role/lifecycle columns, roster operation/receipt table, repository listeners |
| Timed summon | ADR 0011, one lease model, signed-time helpers, crash tests | current world boundary, placement, snapshot/alias/roster/group composition | old session/snapshot/operation tables, mutable session cache, feature recovery scanner |
| Provisioning | ADR 0012, immutable provenance and deterministic IDs, operation tests | current profile creation, optional roster/group participant, activation world boundary | old ten-state coordinator, provisioning journal, command-link intent table, resumption maps |
| Resolved capture | capture policy spec, entropy and terminal-result tests, source receipt invariants | integrate into current capture operation and current receipt-first actor-save boundary | old `CaptureAttemptRepository`, private journal/runtime recovery graph |
| Tame/link capture | HyDragon contract, live component behavior, convergence/crash tests | one atomic current capture operation with roster/group/lease participants and ECS-safe service | callback chain that commits roster then lease then capture; source-string architecture tests |
| Paid revival | ADR 0013, multi-item recipe/quote models, receipt outcomes, forked crash matrix | current restoration boundary, generic refund recipe, roster/group/lease participants, UI | paid-revival journal/cost/reservation/refund/apply-plan tables and large coordinator/repository |
| Captured-item coop intake | exact item fingerprint/retirement ordering tests and portable snapshot invariants | source variant of current normalized coop capture plus current held-slot receipt durability | deleted managed-coop runtime graph, lifecycle operation index, custom recovery services |

## Explicit non-donors

Do not restore:

- `BONDED_VESSELS`, vessel bindings, generation-state items, repair/reissue
  services, or `Vessel` config;
- `COMPANION_INVENTORY` persistence/API/runtime;
- any schema v5-v9 migration path;
- old feature switches, dual writes, service locators, direct repository
  consumers, per-feature SQLite connection ownership, or pre-commit projection
  callbacks;
- classes over the current architecture ceiling without responsibility-based
  decomposition.

## Initial architecture budget

| Metric | Current reduced core | Recovery ceiling |
| --- | ---: | ---: |
| Feature descriptors | 7 | 13 |
| Operation kinds | 10 | 20 |
| Tables | 19 | 29 |

Any proposed excess requires an ADR before implementation. The ceiling is not a
target and does not permit copied authority.
