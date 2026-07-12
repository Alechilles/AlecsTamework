# Spawner Config (TwSpawnerConfig)

## Overview
Spawner items use `TwSpawnerConfig` assets to control capture and spawn behavior. These assets are converted into per-item feature configs at runtime and executed through `TameworkSpawn` + spawner services.

## Runtime Architecture (Contributor View)
Spawner runtime is split into an orchestrator plus focused services:
- Orchestrator: `SpawnerFeatureHandler`
- Policy + validation: `SpawnerCapturePolicyService`, `SpawnerRolePolicyService`, `SpawnerOwnershipPolicyService`
- Metadata + identity/state: `SpawnerCaptureMetadataService`, `SpawnerNpcIdentityService`, `SpawnerNpcStateService`, `SpawnerItemStackMetadataService`
- Placement/effects/inventory: `SpawnerSpawnPositionService`, `SpawnerEffectService`, `SpawnerPlayerInventoryService`
- Capture finalization and linked-companion sync: `SpawnerCaptureFinalizerService`, `SpawnerLinkedNpcSyncService`

When extending spawner behavior, add logic to these service domains instead of centralizing it in the orchestrator.

## Asset location
`<ModRoot>/Server/Tamework/Items/Spawners/*.json`

## Core fields
- `EmptyItemId` (required). The empty spawner item id to bind this config to.
- `FilledItemId` (optional). The filled variant item id, if used.
- `IconDefault` (optional). Default icon override used for filled items.
- `TooltipMode` (optional, default `Additive`). Controls captured-spawner item description composition.
  - `Additive`: appends Tamework lines (`Species`, `Gender`, and resolved attachments) to the base item description.
  - `Replace`: writes only Tamework lines as the captured item description.

## AllowedRoles
Controls which NPC roles can be captured or spawned.

Fields:
- `Mode`: `AllowAll`, `Allowlist`, or `Denylist`
- `Allowlist`: list of role ids
- `Denylist`: list of role ids

## Capture settings
Fields:
- `RequireTamed` (default true). Only allow capture if NPC is tamed (Tamework tamed component or a role id that starts with `Tamed`).
- `OwnerRestricted` (default true). If true, only the owner can capture.
- `RequireOwner` (optional override). If set, explicitly require or skip owner checks.
- `ParticleSystem` (optional). Particle system to play on capture.
- `SoundEvent` (optional). Sound event to play on capture.
- `CooldownMs` (optional). Per item capture cooldown.
- `MaxDistance` (optional). Max distance for capture.

## Spawn settings
Fields:
- `OwnerRestricted` (default true). If true, only the owner can spawn.
- `RequireOwner` (optional override). If set, explicitly require or skip owner checks.
- `ParticleSystem` (optional). Particle system to play on spawn.
- `SoundEvent` (optional). Sound event to play on spawn.
- `CooldownMs` (optional). Per item spawn cooldown.
- `MaxDistance` (optional). Max distance for spawn.

Captured Tamework NPC names are stored on the spawner item and restored on spawn.
Captured attachment IDs are stored on the spawner item and can be displayed with player-friendly labels from
`TwAttachmentDisplayConfig`.

Filled spawners also carry the canonical companion profile identity used by population accounting. Capture moves an owned companion to `CAPTURED`: it keeps consuming one owner slot unless capture clears ownership, but it stops occupying a physical claim. Spawn/release reserves destination owner and claim capacity before creating the NPC. The exact source stack is finalized only after a live NPC with the planned canonical identity exists; a denial or pre-spawn failure keeps the filled item intact, while a late durability failure is reported as degraded.

Older filled items without a canonical profile ID are adopted through their stable legacy identity. Adoption is cap-checked and cannot create a second active representation of the same companion. Existing over-cap legacy companions are preserved during upgrade reconciliation, but a copied or newly restored item cannot bypass later admissions. A provisional legacy identity is promoted when its owner admission commits or released exactly once after denial/cancellation; retries and late callbacks cannot double-release it.

`Capture.ClearsOwner` and `Spawn.AssignsOwner` are controlled by `/tw settings`. Older configs that still contain those fields continue to load, but new item configs should not author them.

The spawn transition follows all four runtime-setting combinations:

| `CaptureClearsOwner` | `SpawnSetsOwner` | Spawned owner | Spawn owner delta | Spawn claim delta |
| --- | --- | --- | ---: | ---: |
| `false` | `false` | Stored owner, or unowned if none was stored | Stored owner `0`; unowned `0` | Stored owner `+1`; unowned `0` |
| `false` | `true` | Stored owner when non-null; otherwise spawning player | Stored owner `0`; null-to-owner `+1` | `+1` when owned |
| `true` | `false` | Unowned | `0` | `0` |
| `true` | `true` | Spawning player | `+1` | `+1` |

The deltas assume the captured source itself occupies no physical claim. A canonical unowned profile restored to a non-null owner always uses normal cap-checked null-to-owner admission; it is never treated as an existing-owner zero delta. Conversely, a canonical non-null stored owner cannot be silently transferred or cleared by restore.

## Icon overrides
Optional overrides for filled spawner icons based on attachments or role.

Fields:
- `IconOverrides`: array of overrides with `Icon` and `Attachments` map.
- `IconOverridesByRole`: map of role id to override arrays.
- `IconOverrideGroups`: ordered array of shared role groups with `Roles`,
  optional group `IconDefault`, and `Overrides`.

Attachment maps use the NPC attachment keys as the match criteria.
Runtime lookup checks exact role overrides first, then the first matching shared
role group, then that group's `IconDefault`, then global overrides, then the
top-level `IconDefault`. Use group `IconDefault` for roles whose captured NPCs
have no attachment variants but still need their own base-skin icon.

The current spawner icon tooling guide lives in the wiki:
[Spawner Icon Generation](../wiki/Modder-Documentation/System-Integration/Spawner-Icon-Generation.md).

## Example
```json
{
  "EmptyItemId": "Spawner_Tamework_Example",
  "FilledItemId": "*Spawner_Tamework_Example_State_Filled",
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [ "Mob_Tamework_Interact_Test" ]
  },
  "Capture": {
    "OwnerRestricted": true,
    "ParticleSystem": "Poof_Small",
    "SoundEvent": "SFX_Tamework_Poof",
    "CooldownMs": 500,
    "MaxDistance": 5
  },
  "Spawn": {
    "OwnerRestricted": true,
    "ParticleSystem": "Poof_Small",
    "SoundEvent": "SFX_Tamework_Poof",
    "CooldownMs": 500,
    "MaxDistance": 5
  }
}
```

## Reloading
Use `/tw reloadconfig` to reload spawner, naming, and command item configs into the item feature registries.
Captured spawner display text is written into base Hytale `ItemDisplay` metadata when the NPC is captured.
