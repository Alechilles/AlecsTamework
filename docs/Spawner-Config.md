# Spawner Config (TwSpawnerConfig)

## Overview
Spawner items use `TwSpawnerConfig` assets to control capture and spawn behavior. These assets are converted into per-item feature configs at runtime and executed through `TameworkSpawn` + spawner services.

## Runtime Architecture (Contributor View)
Spawner runtime is split into an orchestrator plus focused services:
- Orchestrator: `SpawnerFeatureHandler`
- Policy + validation: `SpawnerCapturePolicyService`, `SpawnerRolePolicyService`, `SpawnerOwnershipPolicyService`
- Metadata + identity/state: `SpawnerCaptureMetadataService`, `SpawnerNpcIdentityService`, `SpawnerNpcStateService`, `SpawnerItemStackMetadataService`
- Placement/effects/inventory: `SpawnerSpawnPositionService`, `SpawnerEffectService`, `SpawnerPlayerInventoryService`
- Source-item finalization: `SpawnerSourceItemTransaction`
- Companion persistence: `CaptureFlow`, `ReleaseFlow`, and `CaptureItemFlows`
  under `companion/flow` and `companion/item`

When extending spawner behavior, add logic to these service domains instead of centralizing it in the orchestrator.

The obsolete `ItemFeatureConfig` whistle fields have been removed. Java integrations
must remove calls to `isWhistleEnabled()`, `getWhistleRadius()`,
`Builder.whistleEnabled(...)`, and `Builder.whistleRadius(...)` and rebuild.
These fields had no runtime effect; no spawner asset migration is needed.

## Asset location
`<ModRoot>/Server/Tamework/Items/Spawners/*.json`

## Core fields
- `EmptyItemId` (required). The empty spawner item id to bind this config to.
- `FilledItemId` (optional). The filled variant item id, if used.
- `IconDefault` (optional). Default icon override used for filled items.
- `TooltipMode` (optional, default `Additive`). Controls captured-spawner item description composition.
  - `Additive`: keeps the base item description, adds a blank line, and then writes the Tamework tooltip.
  - `Replace`: writes only the Tamework tooltip as the captured item description.

The Tamework tooltip starts with a compact companion summary. It includes the saved name and species,
an abbreviated color-coded gender when available, and the current and maximum level when progression
metadata and its config are available. A Traits section shows each saved trait's player-facing name,
current value, configured maximum possible value, and signed percentage relative to its default. Trait
values fade from white toward green above the default and toward red below it. An Appearance section
lists friendly attachment labels from `TwAttachmentDisplayConfig`.

## AllowedRoles
Controls which NPC roles can be captured or spawned.

Fields:
- `Mode`: `AllowAll`, `Allowlist`, or `Denylist`
- `Allowlist`: list of role ids
- `Denylist`: list of role ids

## Capture settings
Normal capture items preserve a living NPC's health when they release it. They
do not heal it. Capture is rejected if the NPC already has a death state or has
reached zero health, because that terminal state cannot produce a safe filled
item.

Fields:
- `RequireTamed` (default true). Only allow capture if NPC is tamed (Tamework tamed component or a role id that starts with `Tamed`).
- `TamesTarget` (default false). Enables wild capture: the target must be unowned and untamed, and capture atomically assigns the interacting player as owner while moving the companion into the `CAPTURED` lifecycle.
- `MaxHealthPercent` (optional, `0-100`). Requires target health to be at or below this percentage at both channel start and completion.
- `RequiredEffectId` (optional). Requires this entity effect to be active at both channel start and completion (for example, `Tw_Status_Tranquilized`).
- `ChannelAuraEffectId` (optional). Entity effect applied to the target by the `Begin` channel phase and removed by `Cancel` or `Complete`.
- `ChannelSoundEvent` (optional). A one-shot sound event played at the target when the `Begin` channel phase succeeds.
- `TamedRoleOverrides` (optional map). Maps each capturable wild role to the role stored in the filled item. A mapped role is required when `TamesTarget` is enabled.
- `OwnerRestricted` (default true). If true, only the owner can capture.
- `BlockIneligibleHolders` (default true). Applies while the server setting
  `captureItemOwnership` is `FOLLOWS_ITEM` (see "Captured companion ownership"
  below). A player who could not take ownership (at their owned or group limit)
  cannot pick the item up or take it from a chest; the item stays where it is
  and the player sees a message. It uses inventory slot filters, so set it to
  `false` on every item config if another mod sets its own filters on player
  inventory slots.
- `RequireOwner` (optional override). If set, explicitly require or skip owner checks.
- `ParticleSystem` (optional). Particle system to play on capture.
- `SoundEvent` (optional). Sound event to play on capture.
- `CooldownMs` (optional). Per item capture cooldown.
- `MaxDistance` (optional). Max distance for capture.
- `ChanceMode` (default `Guaranteed`). `Guaranteed` preserves deterministic
  capture and bypasses role capture policy; `Probability` opts into API 0.9
  capture-policy resolution.
- `Power` (default `0`). Non-negative generic capture-item power.
- `BaseChance` (default `1.0`). Base probability in `[0,1]`.
- `ChancePerPower` (default `0.0`). Non-negative additive chance for each power
  point above the role's minimum.
- `MinimumChance` / `MaximumChance` (defaults `0.0` / `1.0`). Inclusive
  probability clamps.
- `FailureCooldownMs` (default `0`). Cooldown applied after one resolved failed
  probability roll.
- `FailureParticleSystem` / `FailureSoundEvent` (optional). Failure feedback.
- `SourceConsumption` (default `SuccessOnly`). `SuccessOnly` spends the source
  only on success; `ResolvedAttempt` spends it after either terminal success or
  terminal failed roll.
- `SuccessDisposition` (default `CapturedItem`). Supported values are
  `CapturedItem`, `TameAndCommandLink`, and `StoreBondedCompanion`.
- `BondedRosterId` (required only for `StoreBondedCompanion`). Names the
  separate bonded roster receiving the stored profile.
- `CommandFamilyId` (required only for `TameAndCommandLink`). Names the generic
  owner/command-family roster.
- `RequiredCommandConfigId` and `RequireCommandAccessItem`. Fence the capture
  to a compatible command access item. `StoreBondedCompanion` requires both a
  command-config ID and `RequireCommandAccessItem: true`.
Role-side minimum power, resistance, multiplier, missing-health bonus,
guaranteed power, and custom requirements live in
`Server/Tamework/CapturePolicies/*.json`. See the
[TwCapturePolicyConfig reference](../wiki/Modder-Documentation/Config-Reference/TwCapturePolicyConfig-Reference.md).

### StoreBondedCompanion

`StoreBondedCompanion` is the capture entry point for an ephemeral bonded
roster. It is separate from filled spawners and generic tame/link capture.

The route validates the command-access item, owner policy, allowed source and
tamed roles, resolved bonded family, capacity, capture policy, distance,
required effect, and exact config generation before rolling or spending the
source. On success it:

1. takes the complete NPC snapshot and capture evidence;
2. commits one `STORED` bonded record on the companion index, with the
   original source NPC identity as capture evidence;
3. removes that exact source NPC;
4. finalizes source-item consumption according to `SourceConsumption`; and
5. emits the completion feedback once.

The index commit happens before the source NPC is removed. The capture does
not create a filled spawner or a command-family membership. The bonded record
counts toward the owner's owned limit.

When `TamesTarget` is enabled, every eligible source role must have a
`TamedRoleOverrides` target role. The target role must select exactly one
family in `BondedRosterId`, and that family's `Features.Capture` must be
enabled.

Capture success particles and sounds are post-commit feedback. For a channeled
item, author the sustained aura/sound in the Begin phase and one completion
effect in the Complete path. Do not duplicate the same completion effect in
both the item interaction and spawner success fields.

## Spawn settings
Fields:
- `OwnerRestricted` and `RequireOwner`. Retired. They still load and are ignored: the server's
  captured companion ownership mode decides who may release (see below).
- `ParticleSystem` (optional). Particle system to play on spawn.
- `SoundEvent` (optional). Sound event to play on spawn.
- `CooldownMs` (optional). Per item spawn cooldown.
- `MaxDistance` (optional). Max distance for spawn.

Captured Tamework NPC names are stored on the spawner item and restored on spawn.
Captured attachment IDs are stored on the spawner item and can be displayed with player-friendly labels from
`TwAttachmentDisplayConfig`.

## Captured companion ownership

A capture never clears the owner: an owned companion keeps its owner while it
is in a capture item. A tamed animal without an owner, and a wild
animal caught by an item with `TamesTarget`, belong to the capturing player. A
wild animal caught by an item that does not tame it stays an unowned wild
capture and is released wild.

The server setting **Captured companion ownership** in `/tw settings`
(`ownership.capture.captureItemOwnership` in `tamework-settings.json`) decides
how the owner changes. It applies to every capture item.

| Mode | Behavior |
| --- | --- |
| `FOLLOWS_ITEM` (default) | The owner becomes whoever gets the filled item into their inventory (pickup, chest, `/give`), if their companion limits allow it. Putting the item into a chest does not change the owner. `BlockIneligibleHolders` stops a player at their limit from picking it up. A player who still holds an item they do not own becomes the owner when they release it, if their limits allow. |
| `OWNER_ONLY` | The item is bound to its owner. Other players cannot pick it up, take it from a container or release it. |
| `CHANGES_ON_RELEASE` | The 4.x rule. The owner stays the same while the companion is in the item, anyone can carry it, and whoever releases it becomes the owner if their limits allow. |

The mode alone decides who may release a captured companion; `OWNER_ONLY` is
the restriction. The former spawn owner checks (`Spawn.OwnerRestricted`,
`Spawn.RequireOwner` and the "Spawn requires owner" server setting) are
retired: the fields and the saved `spawnRequiresOwner` value still load without
error and are ignored for release. A filled item that no longer matches its
companion (after a Recall, a Forget or a later capture) turns into an empty
item when anyone uses it, in every mode.

A filled item shows its owner on the last tooltip line. The line follows the
owner when the item changes hands in `FOLLOWS_ITEM`.

Retired fields: `Capture.ClearsOwner`, `Spawn.AssignsOwner` and the
interaction-level `SpawnAssignsOwner` still load without error and are ignored.
`Capture.OwnershipFollowsHolder` was replaced by the server setting before
release. The old `captureClearsOwner` and `SpawnSetsOwner` values in
`tamework-settings.json` are read only when the file has no
`captureItemOwnership` value:

| Old `captureClearsOwner` | Old `SpawnSetsOwner` | Mode |
| --- | --- | --- |
| true | true | `FOLLOWS_ITEM` |
| true | false | `FOLLOWS_ITEM` |
| false | true | `CHANGES_ON_RELEASE` |
| false | false | `OWNER_ONLY` |

A missing old value counts as `true`. With neither present the mode is
`FOLLOWS_ITEM`. Saving `/tw settings` writes the mode and drops the old values.

Releasing a filled spawner respawns the stored NPC from its saved snapshot
(`ReleaseFlow`) and empties the item only after the release succeeds. A filled
item carries the companion's profile id and generation. Each release or
recapture raises the generation, so a duplicated or stale copy of the item is
refused and emptied when it is used. A supported coop interaction can instead
move a filled item directly into a coop slot.

A destroyed or despawned filled item makes its companion `LOST`; the owner can
recover it from the linked panel.

Capture items written by older versions still work (`LegacyItemAdoption`):

- A 4.x item releases its imported companion once; copies are refused.
- A 2.x item that was never rewritten restores its companion from the item's
  own data on first use; copies are refused.
- A tamed companion with no owner in an imported item becomes the releaser's,
  within their limits.

Configured capture/spawn particles and sounds are success feedback. Tamework
commits the index first and plays them only after the capture or release
succeeds. A refused or failed capture or release plays no success effects.

For a hold-to-capture item, run `TameworkCaptureChannel` with `Phase: Begin`, then chain a native `Charging` interaction. Route its zero-second/release branch to `Phase: Cancel` and its completion branch to `Phase: Complete`. The native charge duration remains an item-asset choice; server policy is rechecked on completion before any ownership, item, or NPC state changes are committed.

## Companion icons

Companion appearance mappings live in [TwDynamicIconConfig](../wiki/Modder-Documentation/Config-Reference/TwDynamicIconConfig-Reference.md)
assets under `Server/Tamework/DynamicIcons/`. Capture items and both command
panels resolve the same icon from the NPC role and attachment selections.
Spawner `IconDefault` remains the filled item's fallback when no companion icon
matches. The old inline `IconOverrides`, `IconOverridesByRole`, and
`IconOverrideGroups` fields are removed; migrate those maps to dynamic icon assets.

See [Spawner Icon Generation](../wiki/Modder-Documentation/System-Integration/Spawner-Icon-Generation.md)
for generating the PNGs and shared config assets.

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
    "ChanceMode": "Guaranteed",
    "OwnerRestricted": true,
    "ParticleSystem": "Poof_Small",
    "SoundEvent": "SFX_Tamework_Poof",
    "CooldownMs": 500,
    "MaxDistance": 5
  },
  "Spawn": {
    "ParticleSystem": "Poof_Small",
    "SoundEvent": "SFX_Tamework_Poof",
    "CooldownMs": 500,
    "MaxDistance": 5
  }
}
```

Bonded capture example:

```json
{
  "EmptyItemId": "Example_Bonding_Stone",
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [ "Example_Wild_Companion" ]
  },
  "Capture": {
    "RequireTamed": false,
    "TamesTarget": true,
    "RequiredEffectId": "Tw_Status_Tranquilized",
    "TamedRoleOverrides": {
      "Example_Wild_Companion": "Tamed_Example_Companion"
    },
    "ChanceMode": "Probability",
    "SourceConsumption": "ResolvedAttempt",
    "SuccessDisposition": "StoreBondedCompanion",
    "BondedRosterId": "example:shared_roster",
    "RequiredCommandConfigId": "ExampleBondedController",
    "RequireCommandAccessItem": true
  }
}
```

## Reloading
Use `/tw config reload` to reload spawner, naming, and command item configs into the item feature registries.
Captured spawner display text is written into base Hytale `ItemDisplay` metadata when the NPC is captured.

Bonded roster policies reload with their dependent command configs as one
coherent generation. A bonded capture against a missing, ambiguous, disabled,
or stale family fails closed and does not fall back to `CapturedItem` or
`TameAndCommandLink`.

The API 0.9 `CAPTURE_POLICY` capability is a separate runtime gate. Loading the
fields or resolving their immutable config views does not prove that the
authoritative probabilistic capture path is active.
