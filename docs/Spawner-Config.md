# Spawner Config (TwSpawnerConfig)

## Overview
Spawner items use `TwSpawnerConfig` assets to control capture and spawn behavior. These assets are converted into per-item feature configs at runtime and executed through `TameworkSpawn` + spawner services.

## Runtime Architecture (Contributor View)
Spawner runtime is split into an orchestrator plus focused services:
- Orchestrator: `SpawnerFeatureHandler`
- Policy + validation: `SpawnerCapturePolicyService`, `SpawnerRolePolicyService`, `SpawnerOwnershipPolicyService`
- Metadata + identity/state: `SpawnerCaptureMetadataService`, `SpawnerNpcIdentityService`, `SpawnerNpcStateService`, `SpawnerItemStackMetadataService`
- Placement/effects/inventory: `SpawnerSpawnPositionService`, `SpawnerEffectService`, `SpawnerPlayerInventoryService`
- Source-item finalization: `SpawnerCaptureFinalizerService`, `SpawnerSourceItemTransaction`
- Canonical persistence: `SpawnerCaptureAuthor`, `SpawnerCapturedArtifactReleaseAuthor`

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
- `TamesTarget` (default false). Enables wild capture: the target must be unowned and untamed, and capture atomically assigns the interacting player as owner while moving the companion into the `CAPTURED` lifecycle.
- `MaxHealthPercent` (optional, `0-100`). Requires target health to be at or below this percentage at both channel start and completion.
- `RequiredEffectId` (optional). Requires this entity effect to be active at both channel start and completion (for example, `Tw_Status_Tranquilized`).
- `ChannelAuraEffectId` (optional). Entity effect applied to the target by the `Begin` channel phase and removed by `Cancel` or `Complete`.
- `ChannelSoundEvent` (optional). A one-shot sound event played at the target when the `Begin` channel phase succeeds.
- `TamedRoleOverrides` (optional map). Maps each capturable wild role to the role stored in the filled item. A mapped role is required when `TamesTarget` is enabled.
- `OwnerRestricted` (default true). If true, only the owner can capture.
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

1. freezes the complete NPC snapshot and capture evidence;
2. durably creates one `STORED` bonded profile in the separate bonded database;
3. records the original source NPC identity so replay cannot create another
   profile;
4. queues/removes that exact source NPC through bounded cleanup;
5. finalizes source-item consumption according to `SourceConsumption`; and
6. emits the completion feedback once.

The durable profile commit happens before source retirement. The operation
does not create a filled spawner, generic profile, command-family membership,
population-group record, timed-summon lease, or generic outbox operation.
Retrying the same attempt uses its original idempotency/capture evidence.

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
- `OwnerRestricted` (default true). If true, only the owner can spawn.
- `RequireOwner` (optional override). If set, explicitly require or skip owner checks.
- `ParticleSystem` (optional). Particle system to play on spawn.
- `SoundEvent` (optional). Sound event to play on spawn.
- `CooldownMs` (optional). Per item spawn cooldown.
- `MaxDistance` (optional). Max distance for spawn.

Captured Tamework NPC names are stored on the spawner item and restored on spawn.
Captured attachment IDs are stored on the spawner item and can be displayed with player-friendly labels from
`TwAttachmentDisplayConfig`.

`Capture.ClearsOwner` and `Spawn.AssignsOwner` are controlled by `/tw settings`. Older configs that still contain those fields continue to load, but new item configs should not author them.

Releasing a filled spawner recreates the stored NPC through the canonical
captured-spawner release operation and consumes the filled item only after the
release succeeds. Ordinary use follows that shared release path. A supported
managed-coop interaction can instead admit an eligible canonical filled item
directly; it retires the item only after durable coop residency publishes.

An exact v2.16.1 filled item can also repair the known 3.0.0-3.0.2 migration
case where its canonical profile was left `UNLOADED` while the capture-v1
snapshot survived only as history. Recovery runs when that item is used; it
reads the existing schema-1 target directly and does not rerun import. It is
refused if the source UUID is loaded, ownership or role conflicts, capture
history is ambiguous, or the profile has moved beyond the initial imported
state.

The same migration-only fallback covers the rarer v2.16.1 case where import
created no companion row at all and the filled item is the only surviving
capture record. It requires a durable supported-public-import manifest, an
exact released-public item with its source UUID and captured role, no matching
profile, alias, or snapshot, and authoritative absence of the source UUID from
loaded worlds. The release transaction creates the missing initial captured
profile and immediately releases it. The import manifest persists when later
startups report `targetOrigin=EXISTING`; a native 3.x world without that
manifest cannot create a companion through this fallback.

The same operation also handles a complete newer 3.x filled item when a
restored database still holds an older exact `CAPTURED` snapshot for that same
profile. Both the item-claimed UUID and the older canonical UUID must be absent
from loaded worlds; conflicting profiles or non-captured lifecycle states are
left unchanged.

Configured capture/spawn particles and sounds are success feedback. Tamework
freezes their asset IDs and position with the operation intent, then emits them
only after the canonical operation publishes. A rejected, retryable, or failed
operation does not play success effects.

For a hold-to-capture item, run `TameworkCaptureChannel` with `Phase: Begin`, then chain a native `Charging` interaction. Route its zero-second/release branch to `Phase: Cancel` and its completion branch to `Phase: Complete`. The native charge duration remains an item-asset choice; server policy is rechecked on completion before any ownership, item, or NPC state changes are committed.

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
    "ChanceMode": "Guaranteed",
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
Use `/tw reloadconfig` to reload spawner, naming, and command item configs into the item feature registries.
Captured spawner display text is written into base Hytale `ItemDisplay` metadata when the NPC is captured.

Bonded roster policies reload with their dependent command configs as one
coherent generation. A bonded capture against a missing, ambiguous, disabled,
or stale family fails closed and does not fall back to `CapturedItem` or
`TameAndCommandLink`.

The API 0.9 `CAPTURE_POLICY` capability is a separate runtime gate. Loading the
fields or resolving their immutable config views does not prove that the
authoritative probabilistic capture path is active.
