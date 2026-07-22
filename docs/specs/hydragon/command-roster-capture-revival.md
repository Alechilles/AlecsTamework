# Command-Roster Capture and Paid Revival Specification

Status: Proposed replacement for the unreleased bonded-vessel subsystem
Target: Tamework 3.x
Consumer: HyDragon `>=3.0.0 <4.0.0`

HyDragon counterpart: [Draconic capture, Dragon Horn, and revival](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/capture-summoning-maintenance.md)

## 1. Goal

Extend Tamework's existing capture, canonical profile, command-item, population, and revival systems so a consumable capture item can tame an NPC in place and atomically add it to an owner-scoped command roster. Add an optional inventory cost to command-panel revival. These are generic, asset-driven capabilities; Tamework must not hardcode dragons, stones, horns, eggs, ore names, or essence IDs.

This design replaces one-companion-per-vessel storage. Physical command items remain player access tools, while canonical command membership is durable per owner and command family.

## 2. Locked decisions

- Existing capture-item behavior remains the default for configs that do not opt in.
- Opted-in `ResolvedAttempt` consumption spends one source item for every durably resolved success or failure.
- No item is spent for preflight denial, cancellation, stale completion, or another condition that prevents the roll.
- `TameAndCommandLink` success tames and role-maps the existing NPC in place, creates/preserves one profile, and adds it to a configured command family.
- Command-family membership is durable Tamework authority. Item metadata is a cache and UI projection only.
- A paid revive consumes its configured inventory cost exactly once and restores the same profile exactly once.
- Capture, roster link, provisioning, population admission, and revival are capability-gated through the public API.
- The bonded-vessel subsystem and capability are removed. Tamework needs no HyDragon migration behavior because HyDragon has never shipped.

## 3. New and changed configuration

### 3.1 `TwSpawnerConfig.Capture`

Add inherited fields:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `SourceConsumption` | enum `SuccessOnly` | `SuccessOnly` preserves current behavior; `ResolvedAttempt` consumes one source item after either a successful or failed terminal roll |
| `SuccessDisposition` | enum `CapturedItem` | `CapturedItem` uses the existing filled-item finalizer; `TameAndCommandLink` keeps the target live and links its canonical profile |
| `CommandFamilyId` | string/null | Required for `TameAndCommandLink`; names the durable roster family |
| `RequiredCommandConfigId` | string/null | Optional exact command config that must resolve for an access item in the actor's supported inventory compartments |
| `RequireCommandAccessItem` | boolean `false` | When true, missing access item denies before roll and consumption |

Example consumer configuration:

```json
{
  "Capture": {
    "ChanceMode": "Probability",
    "SourceConsumption": "ResolvedAttempt",
    "SuccessDisposition": "TameAndCommandLink",
    "CommandFamilyId": "hydragon:dragon_horn",
    "RequiredCommandConfigId": "HyDragonDragonHorn",
    "RequireCommandAccessItem": true
  }
}
```

Validation rules:

- `TameAndCommandLink` requires `TamesTarget: true`, `CommandFamilyId`, an owner-establishing capture path, and a valid tamed-role result.
- `RequireCommandAccessItem: true` requires a resolvable `RequiredCommandConfigId` whose role policy allows the post-capture role.
- `ResolvedAttempt` is valid only for a capture mode with a durable attempt journal and exact source-stack fencing.
- `TameAndCommandLink` does not use `FilledItemId`. An inherited value is ignored; an explicitly authored non-blank value is rejected as contradictory configuration.
- Explicit values and nested inheritance follow the normal Tamework config contract. Invalid combinations are excluded from the compiled registry.

### 3.2 `TwCommandItemConfig`

Add inherited fields:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `CommandFamilyId` | string/null | Stable namespace for command membership shared by all access items in that family |
| `RosterStorage` | enum `ItemMetadata` | `ItemMetadata` preserves existing behavior; `OwnerCommandFamily` uses the durable owner/family roster |
| `ProjectRosterToItemMetadata` | boolean `true` | Allows UI/cache projection without making metadata authoritative |

`OwnerCommandFamily` requires `CommandFamilyId`, `RequireOwner: true`, and profile-capable recipients. All item IDs resolved by that command config are equivalent access keys for the acting owner's family roster.

### 3.3 `TwCompanionConfig.Command.Revive`

Replace the flat HyDragon use of cooldown-only revival with an inherited nested block:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `Enabled` | boolean/current resolved behavior | Enables command-panel revival for the role |
| `GameplayCooldownMs` | integer/current cooldown | Balance cooldown after death; may be zero |
| `Costs` | array `[]` | Exact item stacks required and consumed on successful revival |
| `InsufficientCostMessage` | string/null | Optional localization key override |

Each `Costs` entry has `ItemId` and positive `Quantity`. An explicit array replaces the inherited array. Empty costs preserve free revival for other Tamework users; HyDragon must configure a non-empty cost for every relevant role.

Existing `DeadRespawnCooldownMs`/`DeadRespawnCooldownMins` remain readable during Tamework's own config transition, but the implementation and generated docs should converge on the `Revive` block. This is Tamework schema evolution, not a HyDragon migration requirement.

## 4. Durable command-family roster

### 4.1 Identity

Canonical membership key:

```text
(owner_uuid, command_family_id, profile_id)
```

Required row data:

```text
owner_uuid
command_family_id
profile_id
membership_revision
active_for_bulk_commands
group_id nullable
home_world nullable
home_position nullable
cached_display_name nullable
cached_name_key nullable
cached_role_id nullable
cached_command_state nullable
created_at
updated_at
last_operation_id
```

Live entity UUID, last-known position, status, health, and other projection details are resolved from the canonical profile/lifecycle stores and may be cached. They are not membership identity.

### 4.2 Access-item behavior

- Opening or using any registered `OwnerCommandFamily` item resolves the acting player plus its `CommandFamilyId`.
- All legitimate copies show the same roster and preferences for that owner/family.
- Dropping or transferring an item transfers only the physical access item. It never transfers roster membership or profile ownership.
- Destroying every access item does not delete the roster. Acquiring a replacement restores access.
- Item metadata may cache a signed/revisioned projection for responsive UI, but stale, copied, edited, or missing metadata cannot create or erase membership.
- Existing `ItemMetadata` command configs retain current behavior.

### 4.3 Membership mutation

Roster add, remove, group, active-toggle, and home mutations use optimistic revision fencing and namespaced idempotency keys. Adding the same profile twice is an idempotent success. Removing a row does not release ownership, delete the profile, or cull the NPC unless a separate explicit operation is authorized.

Automatic tame linking and public provisioning may target an owner-command-family roster. The mutation must validate the command config's owner, tame, allowed-role, and max-target policy against the resulting profile.

## 5. Capture transaction

### 5.1 Pre-roll validation

For `TameAndCommandLink` with `ResolvedAttempt`:

1. Resolve the exact source stack, actor, target, spawner config revision, target policy revision, and command config revision.
2. Validate channel, target identity/liveness/role, health, effect, range, owner policy, minimum power, special requirements, and retry cooldown.
3. Validate owner/profile/population mutation readiness.
4. When required, locate and fence one compatible command access item; validate its command family and post-capture role policy.
5. Prepare canonical profile, ownership, population, tamed-role, and roster membership mutations under one operation ID.
6. Persist the unrolled attempt. Any failure through this step cancels without entropy or item consumption.

### 5.2 Resolution and source spend

1. Revalidate all mutable fences on the owning world thread.
2. Atomically resolve the attempt exactly once as success or failure.
3. For `ResolvedAttempt`, transition the attempt to a durable source-consumption state and exact-CAS decrement the configured source quantity by one.
4. If source consumption cannot be proven, do not publish the result or mutate the target. Retry recovery under the same attempt ID.
5. Once consumption commits, a failed result commits its cooldown and feedback; a successful result proceeds to apply.

The source spend and roll form one recoverable logical boundary. A player cannot receive a resolved attempt for free, and a retried callback cannot spend twice.

### 5.3 Failed result

Failure cancels prepared owner/profile/population/roster mutations, leaves the target live and unchanged, commits the configured cooldown, and emits one `FAILED_ROLL` event that reports source consumption. Cosmetic failure cannot change the result.

### 5.4 Successful result

Success:

1. Claims prepared owner/profile/population/roster admission.
2. Applies ownership, tame state, and configured tamed-role mapping to the existing live NPC on the owning world thread.
3. Commits the canonical profile without transitioning it to captured-item storage.
4. Commits one command-family membership row.
5. Commits the attempt and emits one post-commit capture/link result.

The existing NPC remains in the world. There is no filled-item replacement or spawn-item metadata. If apply is interrupted after the recorded success, recovery resumes the same operation and never re-rolls or consumes again.

If recovery proves that a successful apply is terminally impossible because of an internal/runtime fault after source consumption, it cancels every prepared positive mutation and creates one durable replacement-source recovery claim. This compensation is not used for an ordinary failed roll or player-caused invalidation. The operation can resolve to a committed capture or one replacement claim, never both.

## 6. Provision-and-link transaction

Extend companion provisioning so a caller may request an optional command-family membership in the same idempotent operation:

```text
provision(owner, role, populationGroup, initialLifecycle,
          commandFamilyId?, requiredCommandConfigId?, idempotencyKey)
```

This supports HyDragon's Wyvern Egg without a dedicated summon item. A retry returns the same profile and membership. Initial projection is a separate admitted step; projection failure leaves the profile dormant and visible in the roster.

Public request and result objects are immutable. Existing provisioning callers remain source- and behavior-compatible through overloads/default methods or a new versioned request type.

## 7. Command and placement behavior

Owner-command-family rows participate in the existing linked panel, command radial, group management, Locate, Recall, home, death, lost recovery, and status lanes.

- UI rows are sourced from the roster joined with canonical profile/lifecycle state, not from the held item's cached list.
- Loaded and unloaded resolution remains profile-first.
- Recall and recovery preserve normal population admission and cross-world safety.
- Default safe-placement ordering for Recall and command revival searches in front of the player first, then side offsets, then wider fallback candidates. Behind-player candidates are last-resort only and must never be the first valid default.
- Placement failure leaves the profile and roster unchanged and returns a stable reason.

## 8. Paid revival transaction

### 8.1 Quote and confirmation

For a dead roster row, the linked panel resolves the role's current `Revive` configuration and displays every required item and quantity before confirmation. The server re-resolves the config revision and inventory at commit; client UI is never authority.

### 8.2 Commit sequence

1. Resolve actor, owner, command family, profile, death record, role config revision, population group, and target world.
2. Validate revival enablement, ownership, roster membership, dead/revivable state, cooldown, persistence health, population admission, and safe placement.
3. Locate and exact-fence the required inventory stacks. Split costs across stacks deterministically without exceeding the quoted totals.
4. Persist a revival operation and inventory reservation under one idempotency key.
5. Prepare the existing profile/death/population recovery transition.
6. Consume all reserved costs exactly once.
7. Commit revival of the same profile and one safe projection.
8. Commit lifecycle/roster status, release reservations, and emit one result event.

If failure occurs before durable cost consumption, release reservations and charge nothing. After an ambiguous crash, recovery queries the same operation. Terminal inability to finish after a proven charge creates one durable owner refund/recovery claim. It never silently drops the cost.

### 8.3 Invariants

- One dead profile can have at most one live revival operation.
- Duplicate confirmation returns the existing operation result.
- A config reload cannot change the cost of an in-flight operation.
- Inventory movement invalidates stale fences before consumption.
- Successful revival preserves profile ID, name, progression, traits, attachments, and command-family membership.
- Insufficient cost, capacity denial, unsafe placement, or unavailable persistence causes no charge and no projection.

## 9. Public API and capabilities

Replace `BONDED_VESSELS` with granular capabilities:

- `COMMAND_FAMILY_ROSTERS`
- `CAPTURE_TAME_AND_LINK`
- `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`
- `PAID_COMMAND_REVIVAL`

Required public surfaces:

- immutable command-family roster query and idempotent membership mutation;
- capture config views for source consumption and success disposition;
- provision-and-link request/result;
- revive quote, prepare/commit/query result, and recovery status;
- post-commit roster membership, capture, and paid-revival events;
- stable denial/recovery reason codes;
- aggregate, non-player-scoped diagnostics usable from server console.

HyDragon must gate each dependent feature independently. Missing paid revival may disable Revive without disabling ordinary Horn commands; missing tame-and-link must disable Draconic Stone attempts before a roll.

## 10. Persistence and recovery

Add durable storage for command-family membership, source-consumption state where the capture journal does not already cover it, paid revival operations, inventory reservations, and capture/revival refund claims. Schema changes follow Tamework's backup-first transactional migration policy.

Recovery order:

1. hydrate canonical profiles and population authority;
2. reconcile command-family roster references;
3. resume capture attempts that have a resolved outcome or pending source spend;
4. resume provisioning/link operations;
5. resume paid revival and refund claims;
6. expose command UI/actions only after required authorities report ready.

Unknown or contradictory positive state quarantines the operation and fails closed. Diagnostics identify the operation/profile/family and bounded reason without exposing unrelated player data.

## 11. Bonded-vessel removal

Remove:

- `BONDED_VESSELS` capability and `TameworkApi.bondedVessels()`;
- public bonded-vessel views, requests, results, events, and reason codes;
- `TwSpawnerConfig.Vessel` codec/runtime fields;
- binding/generation/state tables, repositories, coordinators, recovery, diagnostics, and self-tests;
- summon/store/repair dispatch based on vessel item state;
- vessel-specific population evidence and inventory cross-links;
- example/default vessel assets and schema documentation.

Before deletion, use repository search and compile failures to enumerate consumers. Convert HyDragon to the new capabilities in the same coordinated development window. No HyDragon compatibility shim, schema import, item reader, alias, or adoption operation is required. Tamework's own development database may receive a migration that drops obsolete tables only if its normal schema policy requires the version transition; this is not a player-facing HyDragon migration.

## 12. Diagnostics and operator commands

All read-only diagnostics must work from console without player identity. Player identity is an optional filter, not a command prerequisite.

At minimum:

- `/tw diagnose command-family [owner] [family]`
- `/tw diagnose capture-attempt <attempt-id>`
- `/tw diagnose provision <operation-id>`
- `/tw diagnose revive <operation-id-or-profile>`
- `/tw api test`

Output reports capability readiness, counts, operation states, queue/recovery health, and bounded incident IDs. Commands that mutate inventories, ownership, or world state remain permission-gated and require explicit targets.

## 13. Implementation map

| Area | Existing anchor | Required work |
| --- | --- | --- |
| Capture config | `TwSpawnerConfig`, `ItemFeatureConfig` | Add/validate/inherit source-consumption and success-disposition fields |
| Capture journal | capture policy persistence/services | Add recoverable one-item spend and tame/link apply states |
| Finalization | `SpawnerCaptureFinalizerService` | Branch captured-item versus in-place tame/link without mixing paths |
| Command config | `TwCommandItemConfig` | Add family ID and roster storage mode |
| Command persistence | `CommandLinkedNpcRecordStore` and SQLite persistence | Add owner/family/profile authority; make item metadata a projection for opted-in configs |
| Command UI/runtime | `CommandItemFeatureHandler` and linked-panel services | Source opted-in rows from canonical roster |
| Provisioning | public/internal provisioning API | Optional atomic family membership |
| Revival | `CommandLinkedNpcDeathService`, `CommandRespawnService` | Cost quote, reservation, exact consumption, recovery/refund |
| Placement | `CommandCompanionPlacementService` | Prefer safe in-front candidates for Recall and Revive |
| Public API | capability/config/event surfaces | Add granular capabilities and remove bonded vessels |
| Persistence | schema/repositories/recovery | Roster, spend, revive, refund, and obsolete-vessel removal |
| Diagnostics | command/API self-tests | Non-player-scoped health and operation inspection |

## 14. Acceptance criteria

### Compatibility

1. Existing configs with omitted new fields preserve current captured-item and success-only consumption behavior.
2. Existing `ItemMetadata` command configs preserve their current link behavior.
3. Invalid field combinations fail asset validation with the asset ID and actionable reason.
4. Parent/child tests cover omitted nested sections, partial overrides, and array replacement.

### Capture

5. Preflight denial and cancellation use zero entropy and consume zero items.
6. A resolved failed roll consumes one configured source item, mutates no NPC/profile/owner state, and commits one cooldown.
7. A resolved success consumes one item, tames the same NPC/profile in place, and creates one roster row.
8. Duplicate and concurrent completions obtain one result and one source spend.
9. Restart at every resolution/spend/apply checkpoint converges without a free roll, double spend, duplicate profile, or duplicate row; terminal internal apply failure produces one replacement-source claim and no capture.
10. Missing required command access denies before roll and spend.

### Roster and commands

11. All legitimate family access-item copies show the same owner roster.
12. Losing all access items preserves the roster; a replacement restores access.
13. Transferring/copying an item does not transfer or duplicate roster authority.
14. Existing panel, command, group, Locate, Recall, home, dead, lost, and cross-world behavior works with roster-backed rows.
15. Recall prefers a safe position in front of the player.

### Provisioning and revival

16. Provision-and-link retries return one profile and one membership.
17. Projection failure leaves a dormant/recoverable roster row.
18. Paid revival quotes and consumes the resolved cost exactly once and revives the same profile once.
19. Insufficient items, cooldown, capacity, placement, permission, or persistence denial charges nothing.
20. Restart at each paid-revival checkpoint converges to no charge/no revive, one charge/one revive, or one refund claim.

### Removal and operations

21. Tamework compiles and passes tests with no bonded-vessel capability, API, config, persistence, runtime, diagnostics, docs, or examples.
22. No HyDragon-specific migration or compatibility code is introduced.
23. Required diagnostics run from server console and accept an optional player filter.
24. Packaged HyDragon integration proves failed-stone spending, live tame/link, Egg provision/link, Horn replacement, death, paid revival, and restart recovery.

## 15. Delivery order

1. Add command-family roster schema, config, runtime, UI sourcing, API, and tests.
2. Add capture source-consumption policy and tame/link finalizer.
3. Add provision-and-link.
4. Add paid revival and in-front placement.
5. Convert HyDragon assets/runtime and packaged integration tests.
6. Remove bonded-vessel code, schema/API/config/docs, then run repository-wide reference checks.
7. Run clean unit/integration/package suites and live-server acceptance.
