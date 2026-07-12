---
title: "TwCoopConfig Reference"
order: 24
published: true
draft: false
---
# TwCoopConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwCoopConfig` defines Tamework-managed coop behavior for a specific `CoopId`. It controls who can be captured into the coop, what residents can live there, how produce is generated, and how identity is preserved during release.

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Items/Coops/*.json`
- Scope: coop-scoped
- Resolution key: `CoopId`
- Resolution: highest enabled `Priority` for the requested coop id

Compatibility note:
- Current assets live under `Items/Coops`. Older references to `Farming/Coops` are stale.

## Authority Boundary
An enabled config hands the matching coop's occupancy and resident lifecycle to Tamework. Its managed-slot ledger is the capacity authority, and Tamework owns capture, housing, release, scheduling, and produce transitions for that coop.

Coops without an enabled matching config remain purely vanilla. Tamework does not maintain a shadow ledger for them. This is the post-overhaul boundary: it deliberately does **not** revive the older v2.5 hybrid where vanilla residents were observed and mirrored into a second Tamework representation. That hybrid required repeated slot inference and UUID remapping and was vulnerable to state drift.

## Vanilla field parity

Hytale `0.5.6` exposes seven lifecycle fields on `FarmingCoopAsset`. A managed coop deliberately reads the parallel `TwCoopConfig` values instead of calling vanilla resident mutation. Keep both assets aligned when the same coop must also behave sensibly without Tamework.

| Vanilla `FarmingCoopAsset` field | Managed Tamework field | Managed-coop treatment |
| --- | --- | --- |
| `MaxResidents` | `LifecycleRules.MaxResidents` | Tamework's managed-slot ledger is the sole capacity authority. Existing over-capacity imports remain visible as overflow, but new intake is blocked. |
| `ProduceDrops` | `ProduceRules.DropsByRole` | Tamework generates managed produce by resident role. It does not call vanilla coop production for a managed block. |
| `ResidentSpawnOffset` | `LifecycleRules.ResidentSpawnOffset` | Tamework uses the offset in its durable release operation and placement search; vanilla deployment is not invoked. |
| `ResidentRoamTime` | `ResidentRoamStartHour` and `ResidentRoamEndHour` | Tamework owns the roaming/housing schedule, including ranges that cross midnight. |
| `CaptureWildNPCsInRange` | `LifecycleRules.CaptureWildNPCsInRange` | Tamework's candidate scanner and durable capture pipeline own wild intake. |
| `WildCaptureRadius` | `LifecycleRules.WildCaptureRadius` | Tamework applies the radius before occupancy and identity admission. |
| `AcceptedNpcGroups` | `LifecycleRules.AcceptedRoleIds` | This is an intentional replacement, not a direct copy. Tamework uses explicit normalized role ids, then applies tame/owner policy; vanilla NPC-group admission is not consulted. |

Tamework adds `ProduceRules.IntervalGameHours`, `ProduceRules.ItemsPerTick`, `CapturePolicy`, and `IdentityRules`; vanilla `FarmingCoopAsset` has no equivalent identity or full-state snapshot contract. Vanilla `CoopBlock.Residents` is inspected only during explicit legacy import. After creating the durable managed binding and marker, Tamework neutralizes vanilla ownership, verifies absence in the current boot, and only then finalizes the import.

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level object sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays and maps replace the parent value.
- `TwCoopConfig` is not reloaded by `/tw reloadconfig`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "Enabled": true,
  "Priority": 100,
  "CoopId": "Coop_Chicken",
  "CapturePolicy": { "...": "..." },
  "LifecycleRules": { "...": "..." },
  "ProduceRules": { "...": "..." },
  "IdentityRules": { "...": "..." }
}
```

## Section Reference
### `Enabled`, `Priority`, `CoopId`
- `Enabled`
- `Priority`
- `CoopId`: stable coop identifier used by runtime lookup

### `CapturePolicy`
- `RequireTamed`
- `OwnerRestricted`
- `RequireOwner`
- `ParticleSystem`
- `SoundEvent`

### `LifecycleRules`
- `MaxResidents`: max resident count the coop can hold.
- `ResidentRoamStartHour`: game-hour start for resident roaming.
- `ResidentRoamEndHour`: game-hour end for resident roaming.
- `ResidentSpawnOffset`: placement offset used when residents are spawned out of the coop.
- `CaptureWildNPCsInRange`: allows nearby wild residents to be captured automatically.
- `WildCaptureRadius`: range for wild capture.
- `AcceptedRoleIds`: role ids allowed to live in this coop.

`ResidentSpawnOffset` fields:
- `X`
- `Y`
- `Z`

### `ProduceRules`
- `DropsByRole`: map of resident role id to item drop list id.
- `IntervalGameHours`: how often production ticks in game hours.
- `ItemsPerTick`: how many items are produced per tick.

### `IdentityRules`
- `RequireSnapshotOnRelease`: requires a stored resident snapshot before release is allowed.
- `PreserveUUID`: deprecated compatibility field. `false` is the only supported value for an enabled managed coop. `true` makes the managed overlay invalid and produces an actionable warning.

Stable `profile_id`, rather than entity UUID, is the identity contract. A released NPC may receive a new projection UUID; old UUIDs remain aliases of the same profile so command records and recovery can converge without creating a second companion.

## Managed Capture and Release
- Capture writes the full profile snapshot, slot state, and durable lifecycle operation together before retiring the source entity.
- Capture through a supported item uses the same profile transition. A portable item snapshot is not silently stripped by delegating it to an unmanaged vanilla coop.
- Release claims one durable operation and planned projection before spawning. Replayed callbacks address the same operation, and the exact coop block condition is checked again at callback time.
- A removed managed-coop block follows an explicit removal release path; a normal scheduled release cannot silently switch to that path during a race.
- Capturing a parent cancels any pending breeding job that still names that profile or UUID.

## Existing Vanilla Residents
When an existing vanilla coop first becomes managed, Tamework performs an import-only audit:

- immutable source fingerprints are journaled before mutation;
- the cached report remains non-mutating until an operator confirms its exact fingerprint with `/tw coop reconcile <x> <y> <z> confirm <fingerprint>`;
- exact, uniquely bound residents are imported into managed slots without spawning replacements;
- the vanilla source is neutralized only after the managed binding is durable;
- exact absence proof from the current server process is required before the source is considered retired; a restart rechecks and refreshes older proof;
- unsupported layouts, duplicate matches, capacity conflicts, or changing evidence are quarantined and reported instead of guessed.

Use `/tw coop import-status` to see pending or quarantined import work, and use `/tw coop reconcile <x> <y> <z>` to inspect source disposition and overflow at one loaded coop. Approval is process-local and bound to the complete deterministic plan, so changed resident, slot, profile, disposition, conflict, or overflow evidence requires a new confirmation. A conflict intentionally keeps the affected authority fail-closed until evidence can be reconciled.

## Defaults and Cross-System Notes
- The shipped example asset is `src/main/resources/Server/Tamework/Items/Coops/TwCoopConfig_Example_Coop_Chicken.json`.
- `AcceptedRoleIds` and `DropsByRole` are explicit array/map values and replace parent content when authored in a child asset.
- Coop runtime preserves more than produce state. It also uses resident snapshots and identity rules to decide how safe a release path is.
- Managed resident, lifecycle-operation, and import state is stored in the schema-v5 SQLite persistence model. Accepted writes are completion-aware and drained during shutdown.

## Minimal Example
```json
{
  "Enabled": true,
  "Priority": 100,
  "CoopId": "Coop_Chicken",
  "LifecycleRules": {
    "MaxResidents": 6,
    "AcceptedRoleIds": [
      "tamed_chicken"
    ]
  },
  "ProduceRules": {
    "DropsByRole": {
      "tamed_chicken": "Drop_Chicken_Produce"
    },
    "IntervalGameHours": 1,
    "ItemsPerTick": 1
  }
}
```

## Common Pattern Example
```json
{
  "Enabled": true,
  "Priority": 100,
  "CoopId": "Coop_Chicken",
  "CapturePolicy": {
    "RequireTamed": false,
    "OwnerRestricted": false,
    "RequireOwner": false,
    "ParticleSystem": "Entities/Basic/Particles/Love/Hearts",
    "SoundEvent": "SFX_Pet_Interact_Success"
  },
  "LifecycleRules": {
    "MaxResidents": 6,
    "ResidentRoamStartHour": 6,
    "ResidentRoamEndHour": 18,
    "ResidentSpawnOffset": {
      "X": 0.0,
      "Y": 0.0,
      "Z": 3.0
    },
    "CaptureWildNPCsInRange": true,
    "WildCaptureRadius": 10.0,
    "AcceptedRoleIds": [
      "chicken",
      "tamed_chicken"
    ]
  },
  "ProduceRules": {
    "DropsByRole": {
      "Chicken": "Drop_Chicken_Produce",
      "Tamed_Chicken": "Drop_Chicken_Produce"
    },
    "IntervalGameHours": 1,
    "ItemsPerTick": 1
  },
  "IdentityRules": {
    "RequireSnapshotOnRelease": true,
    "PreserveUUID": false
  }
}
```

## Gotchas
- `CoopId` is the lookup key. Keep it stable once content ships.
- `DropsByRole` keys must match the role ids your coop will actually host.
- Do not combine an enabled `TwCoopConfig` with a second custom resident sidecar or direct vanilla resident mutation for the same coop.
- Keep `IdentityRules.PreserveUUID` omitted or `false`; UUID preservation is no longer a supported managed-coop mode.
- Import reflection is restricted to auditing old vanilla residents. Normal managed operation does not infer vanilla resident slots.
- Coop configs are not part of `/tw reloadconfig`.

## Related Pages
- [Coop and Feed Trough Guide](/mod/alecs-tamework/coop-and-feed-trough-guide)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)



