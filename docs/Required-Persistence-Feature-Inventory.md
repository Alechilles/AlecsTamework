# Required Persistence Feature Inventory

This document records two deliberately separate persistence boundaries:

1. the generic replacement-persistence recovery completed at `f7c3f046`, with
   the live-capability self-test at `fa4191a8`; and
2. the later bonded-companion lease model used by HyDragon's temporary full
   dragons and Miniwyverns.

The first boundary remains authoritative for permanent world animals, coops,
ordinary command companions, generic provisioning, population groups,
command-family rosters, generic timed summoning, and generic paid revival. The
second boundary does not replace or reuse those authorities.

## Scope and artifact

- Branch: `refactor/persistence-consolidation`
- Cutover commit: `f7c3f046`
- Tested commit: `fa4191a88c45638ff54e1b9dc220c0ccf924fdfa`
- Tamework version: `3.0.0`
- Tested jar: `target/Alec's Tamework! v3.0.0.jar`
- Jar SHA-256:
  `ccc7b5f25f0ab0bdc06d5fb2dc915ea096a4103ae32f97fe9fcb5061b81e7a24`
- Schema-v1 SHA-256:
  `8ad3e6a9783b3fb4f85cf91f14546fd401651249239554ec7f3be76e2b2bf0d5`

The only intentionally excluded donor systems were profile-scoped virtual
companion inventories and obsolete physical bonded-vessel item-state designs.
The generic Miniwyvern path originally recorded below has since been
superseded for HyDragon by the dedicated bonded authority. The generic APIs and
schema remain present for other mods and ordinary companions.

## Dedicated bonded-companion authority

Bonded companions are durable profiles with disposable world projections. The
authority is universe-scoped and stored separately at:

```text
Tamework/Data/bonded-companions.sqlite
```

It advertises one public capability, `BONDED_COMPANIONS`, through
`TameworkApi.bondedCompanions()`. Its readiness depends on its own authority and
database, not generic replacement-persistence startup, repair, evidence gates,
incidents, circuits, or outbox state. Individual world-bound operations validate
their own world and placement context and may return `WORLD_UNAVAILABLE` without
making the bonded API globally unavailable.

The public lifecycle has exactly three states:

| State | Meaning |
| --- | --- |
| `STORED` | Complete durable snapshot, no active projection |
| `ACTIVE` | One exact lease and at most one matching live projection |
| `DEAD` | Positively confirmed death; paid revive required |

Every non-death exit converges to `STORED`, including dismissal, session
expiry, logout, world transfer, missing projection, and duplicate cleanup.
Only confirmed death creates `DEAD`; revival returns to `STORED` and never
summons automatically.

The complete snapshot retains role, owner/tamed state, name, health, needs,
happiness, breeding, progression, traits, talents, life stage, attachments,
command settings, and namespaced extension payloads when those components are
available. Store merges newly observed state without treating unavailable
optional components as deletion.

Final fresh-world schema v1 has seven bonded-only tables:

| Table | Unique authority |
| --- | --- |
| `bonded_schema_history` | Final fresh-world v1 lineage, version, and exact schema hash |
| `bonded_companion_profile` | Stable identity, family, three-state lifecycle, snapshot, policy evidence, death/revive summary |
| `bonded_companion_lease` | One opaque lease token and exact temporary projection |
| `bonded_companion_extension_data` | Owner/profile/namespace JSON with optimistic revision |
| `bonded_companion_cleanup` | Bounded exact source/projection cleanup intents |
| `bonded_companion_operation` | Terminal idempotent capture/provision/store/revive results and payment fencing |
| `bonded_companion_capture_source` | Profile-lifetime proof that one original source NPC was captured once, including its capture snapshot |

The bonded database never registers a generic companion profile, alias,
lifecycle row, command-family slot, population membership, timed-summon row,
generic extension row, or generic projection outbox operation.

One player-facing roster can contain several policy families. Each profile
retains a stable roster/family pair, while capacity, timers, revive recipe, and
feature switches remain family-scoped. UI identity is the stable bonded profile
ID; a live NPC UUID is lease evidence only.

## Restored capability ownership

| Capability | Canonical authority | Shared operation path |
| --- | --- | --- |
| Owner population | Canonical profile/lifecycle plus durable positive reservations and sealed world evidence | Owner population transition/reconciliation participants reused by capacity-changing operations |
| Population groups | Role classification, owner/group membership, and positive reservations | Group assignment plus shared admission participants |
| Command-family rosters | Owner/family/profile membership with stable slots | Roster membership/transition participants |
| Timed summoning and storage | Per-profile timed lease tied to roster and active-cap authority | Timed lease mutation/transition |
| Companion provisioning | Idempotent provisioning entitlement and canonical dormant profile | Provisioning creation followed by separately recoverable activation |
| Resolved capture attempts | Capture operation payload, terminal roll, cooldown projection, and exact inventory receipt | `companion_capture` variant; no new operation kind or table |
| Tame and command link | Live target receipt plus profile, owner, role, groups, roster, and initial lease | `companion_capture` variant with shared participants |
| Paid command revival | Frozen restoration projection, exact recipe receipt, refund authority, admission, roster, groups, and lease | `paid_revival` plus shared restoration and compensation machinery |
| Captured-item coop intake | Canonical captured artifact and coop residency | `companion_coop_capture` item-source variant |
| Historical pre-lease Miniwyvern path (superseded for HyDragon) | Generic provisioning entitlement, dormant profile, command roster, death/revival semantic events | Shared generic provisioning/restoration operations; retained for generic consumers, no longer HyDragon's bonded route |

All live mutation evidence is frozen on the owning world thread and submitted
through the same operation engine. Public events are emitted from one
checkpointed projection observer. Diagnostics, readiness, incidents,
quarantine, shutdown, and retry policy are replacement-runtime services rather
than feature-local infrastructure.

## Feature descriptors

The static registry contains exactly 13 descriptors:

1. `core_identity`
2. `core_lifecycle`
3. `owner_population`
4. `population_groups`
5. `command_roster`
6. `timed_summon`
7. `provisioning`
8. `economic_compensation`
9. `paid_revival`
10. `capture`
11. `death_and_lost`
12. `coop`
13. `extension_data`

## Operation kinds

The registry contains exactly 20 operation kinds. Each kind names one logical
transaction; variants add participants or payload evidence instead of adding
parallel protocols.

| Operation kind | Unique authority |
| --- | --- |
| `companion_profile_mutation` | Canonical profile metadata |
| `companion_alias_rotation` | Current and historical entity aliases |
| `owner_population_transition` | One capacity-changing lifecycle transition |
| `owner_population_reconciliation` | Sealed live-world population evidence |
| `population_group_assignment` | Role classification and group membership |
| `command_roster_membership` | One owner/family/profile membership |
| `command_roster_transition` | Roster state coordinated with lifecycle evidence |
| `timed_summon_lease_mutation` | One per-profile lease record |
| `timed_summon_transition` | Active summon, storage, expiry, and cooldown transition |
| `companion_provisioning` | Idempotent dormant entitlement creation |
| `provisioning_activation` | First recoverable live projection |
| `paid_revival` | Exact charge and same-profile revival |
| `companion_capture` | Live capture, resolved attempt, and tame/link variants |
| `companion_capture_release` | Portable captured profile release |
| `companion_dormant_transition` | Positive death or Lost transition |
| `companion_restoration` | Free restoration and dormant provisioned revival |
| `coop_slot_registration` | Managed coop slot identity |
| `companion_coop_capture` | Live-entity and captured-item coop intake |
| `companion_coop_release` | Coop resident release |
| `profile_extension_mutation` | Namespaced revision-fenced extension data |

## Schema tables

Fresh replacement schema v1 contains exactly 29 tables.

| Table | Unique authority |
| --- | --- |
| `schema_history` | Replacement lineage, version, and exact schema hash |
| `companion_profile` | Stable companion identity and memoized metadata |
| `operation_envelope` | Idempotency key, frozen payload, phase, and outcome |
| `persistence_incident` | Durable scoped incident evidence |
| `persistence_quarantine` | Scope-specific mutation quarantine |
| `companion_alias` | Historical/current entity UUID mapping |
| `companion_lifecycle` | Canonical lifecycle and location |
| `companion_snapshot` | Restorable canonical NPC state |
| `companion_tool_link` | Canonical command-tool link |
| `profile_extension_data` | Namespaced public integration data |
| `operation_participant` | Shared participant phase and compensation state |
| `owner_population_reservation` | Positive owner/world capacity reservation |
| `population_evidence_batch` | Sealed reconciliation observation batch |
| `population_evidence_observation` | Per-profile evidence within a sealed batch |
| `population_group_classification` | Role-to-group classification revision |
| `population_group_membership` | Canonical profile group membership |
| `population_group_reservation` | Positive owner/group admission reservation |
| `command_family` | Owner-scoped command-family identity and revision |
| `command_roster_membership` | Stable profile slot and roster preferences |
| `timed_summon_lease` | Active/storage/cooldown lease authority |
| `provisioning_record` | Idempotent entitlement and activation state |
| `projection_outbox` | Ordered durable publication |
| `projection_checkpoint` | Consumer-specific exactly-once checkpoint |
| `feature_circuit` | Independent feature degradation state |
| `coop_slot` | Managed coop/slot identity |
| `coop_residency` | Canonical resident placement |
| `refund_claim` | Exact terminal compensation claim |
| `refund_claim_item` | Ordered items within an exact refund |
| `import_manifest` | Immutable public-source import provenance |

Resolved capture attempts, tame/link capture, and paid revival add no
feature-specific tables. Captured-item intake adds neither a table nor an
operation kind.

## Complexity result

The correct comparison is against donor `21e01904`, where the intended
features existed, not against the accidentally feature-incomplete
intermediate artifact.

| Measure | Donor implementation | Current implementation | Reduction |
| --- | ---: | ---: | ---: |
| Production Java files | 2,195 | 1,865 | 330 (15.0%) |
| Production Java lines | 384,446 | 302,208 | 82,238 (21.4%) |

The replacement-focused persistence and companion roots contain 451 Java
files and 60,590 lines. Their largest class is 499 lines; none exceeds the
500-line target. Central composition remains bounded:

- `TameworkPersistenceComposition`: 476 lines;
- `TameworkRestoredFeatureComposition`: 220 lines.

The full project still contains older large classes outside this replacement
slice. They remain refactor candidates, but they were not enlarged to host the
restored persistence features.

Generated consolidation inventory reports:

- zero legacy SQLite Java files or schema tables;
- zero gameplay imports of the deleted SQLite runtime;
- zero legacy tracked/untracked submission calls;
- no second writer, operation phase graph, recovery scanner, readiness graph,
  projection journal, or per-feature transaction runner.

This means the 82,238-line reduction is feature-normalized architectural
simplification. The earlier removal of required behavior is not counted as a
success.

## Automated verification of the generic recovery baseline

Tamework:

- 3,001 tests;
- zero failures;
- zero errors;
- one intentional skip;
- focused cross-slice suite: 106 tests;
- architecture/crash/ECS safety suite: 19 tests;
- no prohibited player-component lookup or direct runtime-system ECS writes.

HyDragon, compiled and verified against the exact Tamework jar:

- 131 tests;
- zero failures, errors, or skips;
- 366 JSON assets validated;
- five locales validated;
- capture, roster, timed summon/storage, population, provisioning,
  Miniwyvern continuity, paid revival, and public-event integration covered.

Automated verification is complete. Live fresh-world, restart, cross-world,
cost/consumption, and failure-recovery rehearsals remain mandatory before
release preparation.

## Bonded verification status

The bonded authority has focused API, configuration, schema, store,
state-machine, projection, capture, panel, diagnostics, payment, and safety
tests. HyDragon has focused bridge, capture, Miniwyvern extension/ability,
encounter-eligibility, and asset contract tests.

Final complete-suite, clean-package, manifest/dependency, packaged-asset, and
fresh-world acceptance evidence belongs to the bonded implementation handoff;
it must not be inferred from the historical counts above. Release preparation
remains out of scope until that acceptance pass is complete.
