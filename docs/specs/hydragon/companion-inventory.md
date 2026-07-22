# Profile-scoped companion inventory

Status: Deferred post-MVP design; not part of Public API `0.9.0` or schema v8
Depends on: canonical profiles, ownership/lifecycle policy, SQLite persistence
resilience, and permanent-release coordination
HyDragon counterpart: [Soul Bond and Miniwyvern](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/soul-bond-miniwyvern.md)

## Goal

In a later Tamework update, add an optional, generic backpack to configured
companion roles. Inventory is
owned by the canonical companion `profile_id`, not by a live entity UUID,
command item, command-family roster, captured item, or model attachment. It survives unload, summon,
store, death/revive, role changes, and server restart without duplication or
loss.

This document preserves the future implementation contract, but none of its
config families, tables, API accessors, capability values, UI, or acceptance
tests block the current Tamework/HyDragon MVP. The implementation update must
choose the then-current API and schema versions rather than retroactively add
this surface to API `0.9.0` or schema v8.

The core conservation invariant is:

> Every input stack is present exactly once in a companion-inventory row
> (`ACTIVE` or durable `OVERFLOW` placement, even when locked/quarantined), the
> player destination, or a durable owner/admin recovery claim; never zero copies
> and never two copies.

Capacity shrink and permanent profile deletion may never silently destroy
items.

## Fixed deferred-design decisions

Although implementation is deferred, the later update must preserve these
decisions:

- conservation counts `ACTIVE` and durable `OVERFLOW` rows even while their
  independent guard is `LOCKED` or `QUARANTINED`;
- config removal closes sessions and preserves rows in a config-independent,
  owner-authenticated, withdraw-only overflow/recovery path;
- the first inventory release has no world-drop disposition or durable world-
  drop receipt; unattended recovery remains a durable row or owner/admin claim;
- SQLite slot/claim payloads are enumerated by a dedicated bounded evidence
  source and scanned with one shared depth/container/stack/byte budget;
- `RequireOwner=false` is not public access, and dormant linked access requires
  current exact actor-held command-tool or owner-command-family evidence; and
- claim withdrawal uses a deletion-independent operation journal with no
  required foreign key to the source profile/session.

## Non-goals

- HyDragon-specific backpack art, unlock recipes, elemental storage, or capacity
  progression.
- Serializing authoritative items through `ProfileDataApi` JSON.
- Copying inventory into spawner metadata, command-item metadata, command links,
  coop/death snapshots, or the NPC component.
- Treating native container change events as a durable transaction.
- Automatically giving a prior owner's items to a new profile owner.
- World drops as the sole recovery guarantee.
- Implementing world-drop receipts in the first companion-inventory release;
  all unattended recovery remains durable inventory overflow or a claim.

## Configuration

Add a role-scoped `TwCompanionInventoryConfig` family at:

`Server/Tamework/CompanionInventory/*.json`

| Field | Type/default | Meaning |
| --- | --- | --- |
| `Enabled` | boolean `true` | Disabled config does not resolve for access. |
| `Priority` | integer `0` | Higher wins; equal priority selects case-insensitive lower asset ID. |
| `RoleIds` | string array, empty | Exact canonical roles; explicit arrays replace inherited arrays. |
| `Capacity` | object | Storage and UI layout. |
| `Access` | object | Authorization, range, and lifecycle policy. |
| `ItemFilter` | object | Optional allow/deny item policy. |
| `Disposition` | object | Safe policy for owner clear/transfer and permanent release. |

`Capacity` fields:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `Slots` | integer `0` | Visible active slots; enabled runtime access requires `1..256`. |
| `Columns` | integer `4` | UI layout in `1..9`; does not affect storage identity. |

`Access` fields:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `RequireOwner` | boolean `true` | Only the canonical owner may open/mutate. |
| `MaxDistance` | number `5.0` | Distance to the current live projection when active. |
| `AllowedLifecycles` | string array `["ACTIVE"]` | Supported lifecycle names; explicit array replaces inherited array. |
| `AllowLinkedDormantAccess` | boolean `false` | Allows an authenticated linked UI to withdraw from configured dormant states; it never creates a live projection. |
| `DelegatedAccessPolicyId` | string/null | Namespaced registered policy required before any non-owner access when `RequireOwner=false`; null/missing handler still denies non-owners. |

`ItemFilter` fields:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `Mode` | `AllowAll` | `AllowAll`, `Allowlist`, or `Denylist`. |
| `ItemIds` | string array, empty | Exact item IDs; explicit array replaces inherited array. |
| `AllowNestedContainers` | boolean `true` | Whether full nested item containers may be deposited. |

`Allowlist` with an empty `ItemIds` denies every deposit; `Denylist` with an
empty list and `AllowAll` permit every otherwise valid item. Nested containers
must also satisfy bounded depth/container/stack limits and recursively validate
their contents; a bound breach denies the whole deposit without mutation.

V1 uses one shared immutable safety budget for deposit, decode, recovery,
evidence, and diagnostics: maximum nesting depth `16` (root depth `0`), `4096`
nested containers, `262144` total stacks, `1048576` encoded bytes per root
stack, and `4194304` encoded bytes per mutation. The first exceeded bound rejects
the input or marks persisted evidence incomplete/quarantined; no path silently
truncates. These constants must be centralized with the recursive evidence
scanner rather than independently redefined by UI, codec, and recovery code.

`Disposition` fields:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `OwnerChange` | `RequireEmpty` | `RequireEmpty` or `EscrowToPriorOwner`. Never transfer implicitly. |
| `PlayerRelease` | `RequireEmpty` | `RequireEmpty` or `EscrowToOwner`. |
| `PermanentDeath` | `EscrowToOwner` | `EscrowToOwner`; unknown-owner items go to quarantine. Destructive discard is not a config option. |

### Example

```json
{
  "Enabled": true,
  "Priority": 100,
  "RoleIds": [ "Tamed_Wyvern_Mini" ],
  "Capacity": {
    "Slots": 9,
    "Columns": 3
  },
  "Access": {
    "RequireOwner": true,
    "MaxDistance": 5.0,
    "AllowedLifecycles": [ "ACTIVE" ],
    "AllowLinkedDormantAccess": false,
    "DelegatedAccessPolicyId": null
  },
  "ItemFilter": {
    "Mode": "AllowAll",
    "ItemIds": [],
    "AllowNestedContainers": true
  },
  "Disposition": {
    "OwnerChange": "RequireEmpty",
    "PlayerRelease": "RequireEmpty",
    "PermanentDeath": "EscrowToOwner"
  }
}
```

## Inheritance, resolution, and reload

The family mirrors `TwCompanionConfig` resolution:

- case-insensitive exact role lookup;
- higher priority wins, then case-insensitive lower asset ID;
- omitted top-level sections inherit fully;
- explicit object sections inherit missing nested fields;
- explicit `RoleIds`, `AllowedLifecycles`, and `ItemIds` replace their parent
  arrays; they never append or union;
- scalar zero/false/empty values are explicit overrides;
- the two-argument fallback delegates to the nested-aware overload;
- codec tooltips document section fallback, array replacement, ranges, and safe
  disposition behavior.

Normal asset loaded/removed events validate and atomically swap the role index;
`/tw reloadconfig` remains item-feature-only. In the deferred implementation,
add `COMPANION_INVENTORY` to internal/public config-family enums and emit
`ConfigReloadedEvent` after a valid swap. Invalid updates retain the last valid
index and report the asset/role.

Role/config changes never delete data:

- lower capacity makes out-of-range slots durable withdraw-only overflow;
- higher capacity re-exposes existing slots below the new bound;
- a role with no enabled config retains all rows, closes active sessions, and
  transactionally reclassifies them as withdraw-only overflow. The canonical
  owner may open the config-independent recovery-only profile UI/command; an
  unknown owner is escrowed to an admin quarantine claim before profile
  deletion rather than silently erased;
- filter changes block new deposits but do not evict existing stacks;
- an in-flight session/mutation remains pinned to one config revision and is
  revalidated before apply.

## Canonical storage model

Use a dedicated, backup-first migration at the next schema version available
when this deferred feature is implemented. Do not add these tables to the
current schema v8 migration. The future migration contains these logical
tables:

### `companion_inventories`

- `profile_id` primary key and restricted profile relationship;
- monotonically increasing inventory revision;
- last resolved config ID/revision and capacity;
- session/write state, timestamps, and integrity hash/version.

### `companion_inventory_slots`

- composite primary key `(profile_id, slot_index)`;
- placement `ACTIVE` or `OVERFLOW` (the conservation location);
- independent guard status `AVAILABLE`, `LOCKED`, or `QUARANTINED`, plus the
  owning operation ID/reason when not available;
- full versioned `ItemStack.CODEC` BSON payload;
- envelope version, SHA-256, stack revision, and timestamps;
- foreign-key behavior that blocks profile deletion while authoritative rows
  remain; never `ON DELETE CASCADE`.

The envelope preserves item ID, quantity, durability/max durability, arbitrary
BSON metadata, and recursively nested `ItemStackItemContainer` contents. A
flattened item-ID/quantity record, Gson POJO, or metadata-present boolean is not
authoritative and is forbidden.

### `companion_inventory_operations`

- immutable operation/idempotency ID, profile/config/session revisions;
- direction (`DEPOSIT`, `WITHDRAW`, `MOVE`, `OVERFLOW_WITHDRAW`, `ESCROW`,
  or `CONFIG_REMOVAL`); claim withdrawal uses its deletion-independent journal;
- source/destination fingerprints and full before/after payloads;
- states `PREPARED`, `APPLYING`, `APPLIED`, `COMPENSATING`, `COMMITTED`,
  `CANCELED`, or `QUARANTINED`;
- one nonterminal writer operation per profile.

### `companion_inventory_claims`

Deletion-independent recovery escrow keyed by claim ID and prior owner UUID,
with nullable/audit-only source profile ID, reason, payload envelope, revision,
claim state, and timestamps. Claims do not cascade with profile deletion. An
unknown owner produces an admin quarantine claim rather than data loss.

### `companion_inventory_claim_operations`

Claim withdrawal has its own deletion-independent journal keyed by claim
operation/idempotency ID and claim ID. It stores authenticated owner/admin,
expected claim revision, destination fingerprint, complete payload, operation
state, and timestamps. Source profile/session IDs are nullable audit text only
and have no foreign key to a deletable profile. Claim recovery therefore remains
possible after the companion inventory and profile rows are gone.

## Access and sessions

Phase one uses a validated, explicit deposit/withdraw UI rather than trusting
unrestricted native drag events. A session is bound to player UUID, profile ID,
owner/profile/config/inventory revisions, current lifecycle/projection when
required, and expiration.

Only one writer session is allowed per profile. A second opener is denied or
given a read-only snapshot. Every mutation revalidates:

- session token and expiry;
- canonical owner and `RequireOwner`;
- configured lifecycle, live projection identity, and distance;
- expected inventory/profile/config revisions;
- source/destination exact stack fingerprints and capacity;
- item filter and nested-container policy;
- persistence subsystem readiness.

`RequireOwner=false` never means public access. The canonical owner remains
authorized; a non-owner additionally needs a positive decision from the exact
registered `DelegatedAccessPolicyId` under a Tamework-minted session token.
Missing/throwing/reloaded handlers deny and invalidate the session. The handler
is side-effect-free and receives actor/owner/profile/lifecycle/config context;
events cannot grant access.

`AllowLinkedDormantAccess=true` permits withdrawal only, and only when the
actor also presents one current Tamework-authoritative link to the same profile:
an exact command-tool link whose stack is proven in the actor inventory, or an
exact owner-command-family membership plus compatible access item. A raw
profile ID, old NPC UUID, profile-data field, provisioning record, copied/stale
item, or link held by another player is insufficient. The requested dormant
lifecycle must also appear in `AllowedLifecycles`. The default `false` leaves
the HyDragon Miniwyvern backpack active-only.

Capture, store, death, lost, owner/role change, world transfer, player
disconnect, config reload, or projection UUID remap invalidates or revalidates
the session. UI rendering never blocks on SQLite from the world thread.

## Atomic transfer protocol

Native `ItemContainer` change callbacks are post-transaction and cannot by
themselves guarantee crash safety. Each transfer uses a durable operation and
exact compare-and-set player/container mutations.

### Deposit

1. Validate session, exact player source stack, target slot/revision, filter,
   and capacity.
2. Persist `PREPARED` with the complete stack envelope and before/after
   fingerprints before removing anything from the player.
3. Durably enter `APPLYING`, then on the owning world thread CAS-remove the
   exact quantity/source stack.
4. Persist the companion slot and inventory revision as `APPLIED`.
5. Revalidate conservation/readback, close the source finalization journal, mark
   `COMMITTED`, and acknowledge/update UI.

If the player mutation occurs but the database write fails, recovery uses the
durable payload to finish the destination or refund exactly once. A partial
refund becomes a durable recovery claim; it is not discarded.

### Withdraw

1. Validate/lock the exact active or overflow slot and persist the intended
   player destination/stack result in `PREPARED`.
2. Durably enter `APPLYING`; the source row remains authoritative but locked and
   invisible to other writers.
3. CAS-insert the exact item into the player inventory on its owning thread.
4. Persist slot removal/update and inventory revision as `APPLIED`.
5. Verify the planned destination fingerprint, close source finalization, and
   commit. On failure, compensate the player insertion or restore/unlock the
   source from the journal.

No remainder is ignored. Capacity/insertion preflight plus transaction-result
checks must prove full application. If compensation cannot prove whether an item
is at source or destination, quarantine the operation and block that profile's
writes until reconciliation establishes one copy.

### Move within companion inventory

The profile revision and both slots update in one SQLite transaction; no player
container hop is involved. Swaps and stack merges must retain full metadata and
respect Hytale stack compatibility.

## Overflow and config changes

When capacity shrinks from `N` to `M`, slots with index `>=M` become `OVERFLOW`
in one revisioned reconciliation. They are visible in a withdraw-only section
and reject deposits/moves into them. Tamework does not compact automatically,
drop items, truncate rows, or rely on an item-container resize remainder.

Expansion reclassifies overflow indices below the new capacity as active.
Remaining overflow stays withdraw-only. A server may always permit the owner to
withdraw overflow even when the role config is removed, subject to ownership and
persistence readiness.

When a role loses its enabled config, a system reconciliation operation closes
writer sessions and reclassifies every remaining slot to `OVERFLOW` while
preserving guard status/payload. `/tw inventory recover profile <profile-id>`
owner-authenticates against the canonical or last durable owner and opens a
withdraw-only view: no deposit, move, delegated access, or capacity expansion is
allowed. If there is no authenticated owner or the profile must be deleted,
the disposition coordinator moves rows to deletion-independent claims first.

Decode/hash failure quarantines the individual slot and reports it; it never
returns an empty slot. Admin tooling may inspect/export evidence but requires an
explicit audited action to replace corrupt payloads.

## Lifecycle, ownership, and deletion

- Summon/store/capture/unload/death/revive changes no inventory rows; sessions
  close or revalidate and the same profile data remains canonical.
- Captured items and command projections store only profile identity, never the backpack.
- A role change applies the new config capacity/filter after the role/profile
  transaction and reconciles overflow without deleting stacks.
- Owner transfer/clear obeys `Disposition.OwnerChange`: deny while nonempty or
  atomically escrow every stack to the prior owner before the owner mutation.
  Contents never follow to the new owner implicitly.
- Player-requested release/cull is denied while nonempty by default. An explicit
  escrow action may create durable owner claims first.
- Natural permanent death cannot prompt the player. It joins inventory
  disposition to `CompanionPermanentDeathCoordinator`'s release barrier and
  atomically escrows to the last owner before `RELEASED`.
- Actual profile-tree deletion requires empty inventory rows or a completed,
  deletion-independent claim receipt. Raw `NpcProfileRepository` deletion is
  guarded/package-scoped behind a `CompanionProfileDeletionCoordinator`.
- Unknown-owner items remain in quarantine claims. World-drop disposition is
  not part of the first companion-inventory release and cannot replace a
  durable row/claim.

Inventory does not consume [population-group](population-groups.md) owned or
active slots. A denied population transition leaves inventory and sessions
unchanged except for a safe session invalidation.

## Recovery and evidence

At restart, nonterminal operations recover before new sessions open. Recovery
checks the journal, inventory revision/hash, exact player/saved-container
evidence, and profile lifecycle. It commits forward or compensates exactly once.

SQLite inventory rows are not `ItemContainer` trees and therefore require a
focused `CompanionInventoryPopulationEvidenceSource` registered through
`CustomContainerReconciliationRegistry`. It enumerates only bounded authoritative
slot/claim payloads off the world thread, then delegates each decoded payload's
nested contents to `RecursiveItemContainerEvidenceScanner` using the exact shared
safety budget above. Corruption or any depth/container/stack/byte bound breach
returns explicit partial coverage; population/roster reconciliation must not
treat it as absence. The source reports stable row/claim evidence IDs and never
opens a mutable inventory session.

Add `COMPANION_INVENTORY` to public/internal persistence mutation domains,
incident classification, feature circuits, mutation-availability gating,
integrity snapshots, recovery coordinators, and aggregate telemetry. Storage
degradation freezes affected writes and closes writer sessions; it does not
serve stale mutable containers.

## Public API and events

In the future implementation's API version, add capability
`COMPANION_INVENTORY` and a default fail-closed
`TameworkApi.companionInventories()` accessor. This capability is explicitly
absent from API `0.9.0`. Do not change `RoleScopedConfigView` constructors.

Expose immutable types and bounded operations:

- `CompanionInventoryConfigView` and `CompanionInventorySummaryView`;
- access evaluation/open-session request and decision;
- revisioned/idempotent deposit, withdraw, move, and claim requests;
- result statuses `APPLIED`, `CONFLICT`, `DENIED`, `INVALID_ITEM`,
  `STORAGE_UNAVAILABLE`, and `QUARANTINED`;
- owner recovery-claim summaries and explicit claim operation.

The facade also registers namespaced, side-effect-free delegated-access policy
handlers and returns an `AutoCloseable` generation-fenced registration. This is
the only way `DelegatedAccessPolicyId` can authorize a non-owner. Registration
replacement/reload invalidates issued sessions; absent/throwing handlers fail
closed.

Never expose a mutable backing `ItemContainer`, repository, raw database
connection, or unbounded arbitrary payload list. Full item payload access is
restricted to an authorized session/mutation; diagnostics return counts and
sanitized item IDs only where permitted.

Post-commit immutable events:

- `CompanionInventoryChangedEvent` with operation ID, profile, direction,
  revision, affected slot/count, and timestamps;
- `CompanionInventoryDispositionEvent` for overflow/escrow/quarantine/claim;
- listener exceptions are isolated and events never authorize a mutation.

High-frequency slot-hover/UI changes do not emit public events. Recovered
operations either suppress a duplicate logical event by operation ID or mark a
single replay event as recovered.

## Diagnostics and migration

Schema migration is backup-first, transactional, idempotent, and preserves all
v7 profiles/snapshots/aliases/command links/population and legacy filled-item
data. A failed migration rolls back DDL and schema marker together. Do not store
inventory in `ProfileDataApi` during migration or fallback.

`/tw diagnose inventory [profile]` reports bounded:

- configured/active/overflow/quarantined slot and stack counts;
- inventory/config/profile revision and payload integrity status;
- writer session and oldest nonterminal operation;
- recovery claims and disposition reasons;
- persistence circuit/readiness, incident IDs, and incomplete evidence bounds;
- orphan/duplicate/invalid slot integrity findings.

`/tw inventory recover claim <claim-id>` provides owner-authenticated claim
withdrawal; `/tw inventory recover profile <profile-id>` opens the
config-independent withdraw-only overflow flow described above. Admin
repair/export commands are separate, explicit, confirmed, and audited.
Telemetry includes aggregate operation latency/failure, overflow and quarantine
counts only; never owner/profile IDs, item metadata, or item names.

## Implementation file map

| Area | Existing anchor | Proposed responsibility |
| --- | --- | --- |
| Asset config | new `config/assets/TwCompanionInventoryConfig` and role registry | Codec, inheritance, validation, resolution |
| Registration/UI config | `Tamework` asset registration, override family, config editor/schema adapter | Store/events/path/public family/options |
| Inventory domain | new focused `inventory/companion` package | Registry, session, access, transfer, overflow, disposition, recovery services |
| Hytale inventory bridge | `inventory/PlayerInventoryAccess`, source transaction patterns | Exact CAS, full transaction-result/remainder handling |
| Item codec | new versioned codec using `ItemStack.CODEC` | Full BSON/nested container envelope, hash, validation |
| Persistence | `persistence/sqlite`, operation/recovery/health/incidents | Tables, repositories, journal, migration, circuits |
| Lifecycle/deletion | `CompanionPermanentDeathCoordinator`, release/cull services, guarded profile deletion | Empty/escrow barrier and deletion-independent claims |
| Evidence | `CustomContainerReconciliationRegistry`, new `CompanionInventoryPopulationEvidenceSource`, `RecursiveItemContainerEvidenceScanner` | Enumerate SQLite rows/claims, then scan nested payloads with shared bounds and partial-result reporting |
| API/events | `api`, `api/internal` | Default unavailable facade, views/requests/results/events |
| Window/UI | new validated companion inventory window/session adapter | Explicit deposit/withdraw, owner/distance/lifecycle validation |
| Diagnostics | integrity service, commands, metrics, selftest | Conservation checks, recovery claims, privacy-safe telemetry |

Do not repurpose command-linked NPC inventory repair classes: they repair
command-tool metadata in player inventory and are not pet storage. Avoid known
unsafe patterns that ignore resize remainders, report insertion failure without
proving rollback, or set a slot without checking transaction success.

## Acceptance tests

### Config and inheritance

1. Valid role configs resolve by priority then asset ID; disabled configs do not
   resolve.
2. Omitted sections inherit, partial objects nested-inherit, and explicit arrays
   replace rather than merge.
3. Invalid capacity/columns/distance/lifecycle/filter/disposition values reject
   the asset while retaining the last valid index.
4. Role config reload emits the correct family and pins active mutations to one
   revision.

### Codec and storage

5. Full round-trip preserves ID, quantity, durability/max durability, arbitrary
   BSON metadata, and recursively nested containers.
6. Envelope version/hash detects truncated, corrupted, or partial payloads and
   quarantines without presenting an empty slot.
7. Repository compare-and-set prevents two writers from committing one expected
   revision.
8. Slot/profile/claim constraints prevent cascade deletion and duplicate slot
   authority.
9. Migration is backup-first, idempotent, rollback-safe, and preserves every v7
   table/row plus legacy items.

### Access and UI

10. Only the owner can mutate when required; distance, lifecycle, projection,
    session, and config revisions are revalidated per action.
11. One writer session/profile is enforced across two players, two windows, and
    reconnect/restart races.
12. Capture/store/death/lost/role/owner/config/world changes close or safely
    revalidate sessions.
13. A second reader never observes a locked slot as available.
14. Filter changes block only new deposits; existing disallowed items remain
    withdrawable.
15. Nested-container rejection/allowance follows config without flattening.

### Transfer conservation

16. Deposit, withdraw, move, merge, split, and overflow withdrawal preserve the
    exactly-once conservation invariant, including inventory rows whose
    independent guard status is `LOCKED` or `QUARANTINED`.
17. Source stack CAS rejects moved, changed, consumed, or metadata-modified
    player stacks.
18. Full insertion preflight and remainder settlement are mandatory; no test
    path ignores a remainder or failed transaction result.
19. Duplicate idempotency keys/callbacks produce one mutation/event.
20. Concurrent deposits/withdrawals for one profile yield one writer and no
    loss/duplication.
21. Fault injection before/after every PREPARED, APPLYING, player CAS, APPLIED,
    source-finalization, compensation, and COMMITTED boundary converges to one
    authoritative location.
22. Disconnect/server crash at each boundary recovers or refunds exactly once.
23. Ambiguous evidence quarantines/freezes writes rather than guessing.

### Capacity and lifecycle

24. Shrink reclassifies every out-of-range slot as withdraw-only overflow and
    deletes nothing.
25. Expansion re-exposes eligible overflow indices without copying stacks.
26. Config removal/role change closes sessions, reclassifies retained rows as
    withdraw-only overflow, and exposes owner recovery through
    `/tw inventory recover profile` without requiring a live role config.
27. Summon/store/capture/unload/death/revive and live UUID remap preserve the
    same profile inventory.
28. Population admission denial changes no inventory contents or revision.

### Ownership, release, and deletion

29. `RequireEmpty` blocks owner clear/transfer or player release with active or
    overflow items.
30. Escrow moves every stack to prior-owner claims atomically before owner
    change/release.
31. Natural permanent death completes escrow before its durable `RELEASED`
    barrier.
32. Unknown-owner disposition creates quarantine claims and never drops data.
33. Profile deletion is impossible with slots or without a completed
    deletion-independent claim receipt.
34. Claim withdrawal is owner-authenticated, idempotent, capacity-safe, and
    preserves conservation through partial destination capacity.
35. No content is implicitly transferred to a new owner.

### API, diagnostics, and architecture

36. Old API binaries link; unavailable default facade returns fail-closed
    decisions.
37. Public snapshots are immutable/bounded and never expose mutable containers
    or repositories.
38. Events are post-commit, once logical, bounded, and listener-failure-safe.
39. `/tw api test` covers open/deposit/withdraw/overflow/escrow/unavailable
    fixtures and cleans them up.
40. Diagnostics/integrity report counts, operations, quarantine, claims, and
    corrupt/orphan rows accurately without mutation.
41. Telemetry privacy tests reject owner/profile/item metadata identifiers.
42. Architecture tests forbid ProfileData storage, raw profile deletion with
    items, post-event-only durability, direct world-thread SQLite waits, unsafe
    player component access, and unchecked inventory/remainder operations.
43. Performance tests bound recursive scanning, session work, diagnostic output,
    and allocation; there is no all-profile scan or per-tick item serialization.
44. Every deposit/decode/recovery/evidence/diagnostic path enforces the shared
    depth/container/stack/per-stack-byte/per-mutation-byte limits without
    truncation; persisted over-bound/corrupt data is quarantined and reports
    partial coverage.
45. `RequireOwner=false` still denies a non-owner without the exact configured
    delegated-access handler decision, and handler failure/reload invalidates
    the session.
46. Dormant access is withdraw-only and requires owner/delegated authorization,
    an allowed dormant lifecycle, and a current exact command-tool or
    owner-command-family link accessible to the actor; stale/profile-data/profile-ID-only evidence
    is denied.
47. `CompanionInventoryPopulationEvidenceSource` is registered through
    `CustomContainerReconciliationRegistry`, discovers bounded SQLite slot/claim
    payload evidence, and reports incomplete coverage on decode/bound failure.
48. Claim withdrawal/recovery remains idempotent after source profile and
    inventory deletion because its operation journal has no required
    profile/session foreign key.
49. Config-removed rows can be fully withdrawn or escrowed to claims before
    profile deletion, and no first-release disposition creates an authoritative
    world-drop receipt.
