# Optimized Interactions (TwInteractionConfig)

## Overview
Tamework replaces large NPC interaction instruction trees with a single action call. The flow is driven by `TwInteractionConfig` assets and executed by `TameworkInteract`.

## Asset location
`<ModRoot>/Server/Tamework/Interactions/*.json`

## Config resolution
- If action field `ConfigId` is provided, that config id is used.
- Otherwise the role param named by `TwGlobalConfig.InteractionDefaults.InteractionConfigParam` (default `InteractionConfigId`) is used when present.
- Otherwise the enabled config with the highest `Priority` whose `RoleIds` includes the NPC role is selected.

`Priority` defaults to `0`. Higher values win. For equal priorities, current asset-map iteration order applies.

## Global defaults
Parameter/alarm names used by the interaction system are defined under `TwGlobalConfig.InteractionDefaults`.
If you rename these keys, update role params and/or action overrides accordingly.

Owner-requirement policy for interactions is controlled by `/tw settings`. Legacy `RequireOwner` config fields are still readable where they exist, but the settings value is the effective server-wide owner gate.

## Interaction order
`Interactions` is evaluated in authored order. The first enabled entry whose requirements pass is executed.

## Prompts (optional)
If the role runs `TameworkInteractPrompt`, Tamework shows the first matching entry's prompt.

Per-entry prompt controls:
- `PromptHint` (translation key)
- `ShowPrompt` (hide prompt for specific entries)

Default prompt keys:
- `server.interactionHints.generic`
- `server.interactionHints.tame`
- `server.interactionHints.feed`
- `server.interactionHints.harvest`
- `server.interactionHints.harvestContext`
- `server.interactionHints.mount`
- `server.interactionHints.modeCycle`
- `server.interactionHints.breed`
- `server.interactionHints.custom`

Define these in `Server/Languages/en-US/server.lang` without the `server.` prefix.

## Cooldowns
- Cooldowns are real-time seconds.
- Entry `CooldownSeconds` overrides config `Cooldowns.InteractionSeconds`.
- Alarm id format: `<InteractionCooldownAlarmPrefix>_<ConfigId>_<index>`.
- Prefix comes from `TwGlobalConfig.InteractionDefaults.InteractionCooldownAlarmPrefix`.

Use `/tw getalarm` to inspect cooldown/harvest alarms.

## Preset interactions
Preset entries provide default behavior plus optional `Requires` + `Effects` add-ons.

### Tame
Common fields:
- `UseLovedItems`
- `ItemsInHand`
- `ItemsParam`
- `Role`
- `RoleParam` (overrides `Role` when resolved from the NPC role's authored parameters or exported scopes)

Behavior:
- Requires untamed NPC and matching held item.
- Reserves the destination owner's slot and, when configured, the destination claim's physical slot before changing ownership.
- Counts one canonical owned companion profile even if that companion is later unloaded, captured, cooped, dead-but-revivable, lost, restoring, or dormant.
- Sets tamed + owner, consumes the held item, and performs an optional role swap only after admission succeeds. A denied or failed mutation cancels its reservation and does not consume the item.

### Feed
Common fields:
- `UseLovedItems`
- `ItemsInHand`
- `Heal`
- `ItemsParam`

Behavior:
- Requires tamed NPC and matching held item.
- Also accepts foods supplied by the role's `TwFoodConfig` profile when one is configured.
- Heals/consumes item.
- Applies shared happiness gain (`TwHappinessConfig` or defaults) with trait scaling via `HappinessGainMultiplier`.
- Consumed-feed happiness prefers the role's `TwFoodConfig` food category value, then falls back to `TwHappinessConfig` item/param impulses.
- Applies manual needs refill rules from `TwNeedsConfig.ManualRefill` when configured.
- Companion hydration can also consume feed trough water charges through needs/runtime systems; trough water states can be refilled via compatible bucket interactions.

### Harvest
Common fields:
- `RequireTamed`
- `RequireHarvestable`
- `RequireHarvestAlarmReady`
- `RequireHarvestInteractionContext`

Behavior:
- Uses role params named by `TwGlobalConfig.InteractionDefaults` for harvestability/context.
- Uses the durable `TameworkAlarm` component for optimized harvest cooldowns. The alarm name comes from `TwGlobalConfig.InteractionDefaults.HarvestAlarmName` (default `Harvest_Ready`), but readiness no longer depends on the base-game `Alarm` store.
- Scales the harvest alarm duration with the progression effect key from `TwGlobalConfig.InteractionDefaults.HarvestCooldownMultiplierEffectKey` (default `HarvestCooldownMultiplier`), so packs can decide which trait/talent/level effect modifies harvest timing.
- Runs `$Harvest` state when valid.
- If the role has `HarvestAddItemBucket` or `HarvestAddItemDecoBucket`, optimized harvest atomically transforms a held `Container_Bucket` or `Deco_Bucket` into that filled item before entering `$Harvest`.
- Role param `HarvestBonusMode` controls harvest luck. `DropDuplicate` duplicates loose drops. `CooldownPreserve` does not duplicate drops and can skip the next harvest cooldown, which is intended for container harvests such as milk.

### Mount
Common fields:
- `RequireTamed`
- `RequireOwner`
- `RequireMountable`
- `RequireCrouching`

Behavior:
- Attempts mount via `NPCMountComponent`.
- Uses mount anchor params.
- Hides active custom nameplates while mounted and restores on dismount.
- Roles can opt into Tamework's beta mounted glide controller by setting role param `MountMode` to `TameworkMountedGlide`. Mounted glide still attaches through `NPCMountComponent`, uses native mount movement while grounded, and applies flight movement to the rider's velocity after jump launch or mid-air mounting.
- `MountGlideMovementConfig` is optional and defaults to `Mount`. Use a custom movement config only when the role should override normal grounded mount movement.
- Existing legacy `TameworkMountedGlide` motion controller or body motion entries may remain in older templates for compatibility, but current mounted glide behavior does not require authors to add them.
- See [Mounted Glide Controller](Mounted-Glide.md) for the full setup and tuning fields.

### ModeCycle
Common fields:
- `RequireTamed`
- `RequireOwner`
- `ShowFloatingText`
- `ShowUiMessage`
- `Cycle`

Default cycle when empty: `Hold -> Idle -> Defend`.

Presentation strings such as mode-cycle `Message`, `ShowFloatingText.Message`, and `ShowUiMessage.Message` may be raw text or `server.lang` keys. Use language keys for player-facing copy whenever the text should be translatable.

### Breed
Common fields:
- `RequireTamed`
- `MinHappiness`
- `FertilityBonus`
- `ManualSelectionSeconds`

Behavior:
- Ensures progression state exists.
- Enforces `TwBreedingConfig.Eligibility` gates (`RequireTamed`, `RequireAdult`, `RequireNotSleeping`, `RequireNotInCombat`).
- Uses effective fertility: `(sharedHappiness * FertilityMultiplier) + FertilityBonus`.
- Manual breeding marks the interacted NPC for that player only. The same player must interact with both intended NPCs before `ManualSelectionSeconds` expires.
- Manual breeding is independent from `TwBreedingConfig.PassiveBreeding.Enabled`, the `/tw settings` passive breeding toggle, and the per-NPC breeding enable toggle. Cooldowns and eligibility gates such as tame, adult, ownership, gender, and role compatibility still apply.
- `MinHappiness` is ignored when the happiness system or breeding happiness requirement is disabled.
- When a manually selected pair is found: applies parent cooldown, pair movement, hearts, delayed offspring spawn.
- Manual and passive pairing enter the same birth-job pipeline. A parent can belong to only one active job, and delayed execution can claim that job's spawn transition only once.
- Fertility intentionally resolves a litter of zero through four offspring. Tamework multiplies the two resolved parent fertility factors, clamps the expected litter to four, guarantees the whole-number portion, and uses one fractional roll for at most one additional child. Similar-looking siblings from one admitted litter are not duplicate callbacks.
- `Pairing.MaxNearbySameType` is a hard execution-time limit shared by manual and passive breeding. Live nearby NPCs and already-pending admitted children both consume headroom, so a planned litter may be reduced or rejected rather than exceeding the cap.
- Capturing either parent into a managed coop cancels the pending job. The delayed callback also revalidates both parents before spawning.
- Manual and passive offspring use the same exact, reservation-backed owner/claim admission path. A child with `InheritOwner=false` is unowned and consumes no owner slot; `BreedingRequiresClaim` can still require its physical destination to be claimed.
- One logical breeding attempt derives a stable pair job from the parents and their persisted cooldown generations, then derives each planned child's profile/NPC identities from that attempt. A restart or duplicate callback therefore retries the same child identities, and every admitted unit ends in commit or cancellation exactly once.
- Pairing can require the same role, require different adult roles in one lifecycle family, allow any adult in one lifecycle family, or explicitly allow any role through `TwBreedingConfig.Pairing.RoleCompatibility`.
- If `TwBreedingConfig.Gender.Enabled` and `RequireDifferentGender` are enabled, partner selection also requires one male and one female companion.
- Offspring flow supports baby-role preference, persisted weighted adult-role selection, life-stage initialization, trait/attachment inheritance, and growth timing. `TwBreedingConfig.Inheritance.AttachmentInheritance.ExcludedSets` can leave equipment or other non-genetic attachment sets at the child's model-generated default.
- World-time deadlines are signed. Negative timestamps are valid; only `0` means unset.

## Ownership and claim admission rules

All `SetOwner` effects that add or transfer a non-null owner use Tamework's shared admission authority. Transfers reserve the destination owner before releasing the source owner, so a denial leaves the previous owner unchanged. The same authority is used by tame actions, legacy ownership adoption, spawner and coop restores, recall/teleport, revive, lost recovery, and breeding.

The standalone `TameworkSetOwner` NPC action is asynchronous. When a vanilla instruction list must also tame the NPC, consume its interaction item, change state, or play success presentation, configure those behaviors through the action's `*OnApplied` fields. The bundle runs against a freshly resolved NPC/player only after the reservation is revalidated and claimed and the canonical owner write reports that it was applied. It runs before the journal's final asynchronous population commit, so `*OnApplied` is an applied-mutation continuation, not a post-commit event. A continuation or finalization failure leaves the operation degraded and recoverable instead of reporting ordinary success. `ConsumeHeldItemOnApplied` removes one item only when the live active item still has the item ID captured when the action was scheduled. Ordinary sibling actions execute eagerly and are not safe for irreversible tame success work.

If an applied owner mutation must roll back, Tamework first records `COMPENSATING` durably while both reservations remain held. Derived authority and source state are restored before the canonical owner component; only an exact restored state can close the journal and release capacity. A partial or ambiguous rollback stays quarantined for startup recovery.

`TameworkOwnerComponent` is the canonical live authorization source for ownership mutation and command access. A canonical clear invalidates command-tool links and clears name ownership; a transfer invalidates the prior owner's links and retargets retained name metadata. Command authorization never treats stale link/name owner IDs as ownership. Runtime and Public API damage evaluation use those components only as read-only fallback policy context while the canonical component is unavailable, in the order owner component, command-link owner, then persisted NPC-name owner; this fallback does not transfer ownership or change the population ledger.

`SetOwner` with `Source: Custom` requires a syntactically valid UUID in `Uuid`. `Name` is optional display metadata, not an identity; a blank or malformed UUID rejects the effect instead of being interpreted as an ownership clear.

Claim limits are placement-admission caps, not movement barriers. Owned `ACTIVE` and durably `UNLOADED` profiles occupy their physical claim. `CAPTURED`, `COOP`, `DEAD_REVIVABLE`, and `LOST` profiles retain their owner slot but do not occupy a claim until restored. Natural movement across a claim boundary is allowed; if an unavoidable cross-world move creates a per-world over-cap condition, Tamework preserves the companion, emits a throttled admin warning, increments the `unavoidablePerWorldOverCapRelocations` diagnostic counter, and blocks later positive admissions until occupancy falls.

`DEAD_REVIVABLE` keeps its owner slot but releases physical claim occupancy immediately when revivable death is observed. A permanent cull/release or death with no supported revive path is recorded as an explicit release and frees the slot only when that durable transition commits.

## Custom interactions
`Type: "Custom"` exposes full `Requires` + `Effects` control.

Example:
```json
{
  "Type": "Custom",
  "Requires": { "All": { "IsTamed": true } },
  "Effects": { "SetState": { "State": "Idle" } }
}
```

### Built-in attachment extensions

Tamework reserves the `tamework:` extension namespace for implementation-owned requirements and effects. Downstream packs can use these built-ins without shipping Java:

- Requirement `tamework:model_supports_attachment`: set `Param` to an attachment slot. Optional `Values` require at least one listed option to exist on the current model.
- Effect `tamework:set_attachment_from_held_item`: set `Param` to an attachment slot and provide exact `ItemId=AttachmentValue` entries in `Values`.
- Requirement `tamework:attachment_exchange_available`: provide the same `Param` and `ItemId=AttachmentValue` mapping as the exchange effect. It matches an equipped-item change only when the held item maps to a different supported value, or an empty-hand removal only when the current value has an exact reverse mapping and the model supports `None`.
- Effect `tamework:exchange_attachment`: equips, replaces, or removes one mapped attachment. Replacement consumes the new item and refunds the old mapped item; removal puts the refund in the empty active hotbar slot.

The held-item effect revalidates the live hotbar item, validates the slot and option against the current model, preserves unrelated stored selections, applies the live model, persists the selection, and consumes one item. Failed or already-applied mutations do not consume an item. Do not combine it with `RemoveItemsHand`; consumption is part of the built-in effect.

The exchange effect additionally requires a one-to-one mapping so every attachment value resolves back to exactly one refund item. It settles the model, persisted attachment component, held-item consumption, and refund as one rollback-capable operation. This cannot be safely composed from the generic attachment and inventory effects because those effects do not share a transaction. A stacked held item requires inventory room for the refunded item; a one-item stack is swapped directly in the active slot. Unmapped values, including appearance-only dynamic attachment values, are not removed or replaced by this effect.

```json
{
  "Type": "Custom",
  "Requires": {
    "All": {
      "IsTamed": true,
      "PlayerIsOwner": true,
      "ItemsInHand": [{ "Items": ["Example_Saddle"] }],
      "Custom": [{
        "Id": "tamework:model_supports_attachment",
        "Param": "Saddle",
        "Values": ["Yes"]
      }]
    }
  },
  "Effects": {
    "Custom": [{
      "Id": "tamework:set_attachment_from_held_item",
      "Param": "Saddle",
      "Values": ["Example_Saddle=Yes"]
    }]
  }
}
```

For replacement and empty-hand removal, use the exchange requirement and effect together with identical mappings:

```json
{
  "Type": "Custom",
  "Requires": {
    "All": {
      "IsTamed": true,
      "PlayerIsOwner": true,
      "Custom": [{
        "Id": "tamework:attachment_exchange_available",
        "Param": "Saddle",
        "Values": ["Example_Saddle=Yes"]
      }]
    }
  },
  "Effects": {
    "Custom": [{
      "Id": "tamework:exchange_attachment",
      "Param": "Saddle",
      "Values": ["Example_Saddle=Yes"]
    }]
  }
}
```

### `TameworkCaptureChannel`

Runs one phase of a server-authoritative spawner capture channel. Use it as the first step of a native `Charging` interaction, with `Begin`, `Cancel`, and `Complete` phases.

Optional `Begin` fields:

- `BeamParticleSystem`: world particle system repeatedly emitted from the player's eye/item line to the initially targeted NPC.
- `BeamNativeLength`: authored forward length of that particle system. Tamework scales each short-lived segment to stop at the target. Defaults to `50`.
- `ChannelDurationSeconds`: maximum server-side visual session lifetime. Match this to the charging threshold. Defaults to `3`.

The initial target is locked for the session. Short particle segments are capped and renewed only while the channel is active, so cancel, completion, disconnect, invalid targets, and timeout stop new emission without leaving a persistent particle source.

For a left-click channel, place the root under the item's `Interactions.Primary` key. `Use` is the F interaction and can conflict with NPC interaction options.

### `TameworkLaunchProjectile`
Launches a projectile using a solved high-angle ballistic arc instead of the source entity's current look pitch.

Fields:
- `ProjectileId` required projectile asset id.
- `Target` optional enum: `USER`, `OWNER`, `TARGET`. Defaults to `TARGET`.
- `TargetSlot` optional NPC marked target slot. When present, Tamework first tries the source NPC's marked target in that slot and falls back to `Target` resolution if none is present.
- `YawSpreadDegrees` optional symmetric yaw spread applied after the arc is solved.
- `PitchSpreadDegrees` optional symmetric pitch spread applied after the arc is solved.
- `FailIfNoSolution` optional bool. Defaults to `true`.
- `TrajectoryMode` optional enum: `HIGH_ANGLE` or `DIRECT`. Defaults to `HIGH_ANGLE`.
- `RandomAroundSourceMinRadius` optional inner radius for a random landing point centered on the source entity.
- `RandomAroundSourceMaxRadius` optional outer radius for a random landing point centered on the source entity. When greater than `0`, this mode overrides entity-target resolution.
- `RandomAroundSourceVerticalOffset` optional Y offset applied to the random landing point.
- `ImpactEffect` optional nested block. When present with positive values, the projectile applies an `EntityEffect` in a radius at its final impact position.
- `LingeringHazard` optional nested ground-hazard block. When present with positive values, the spawned projectile creates a hidden lingering damage zone when it dies.

`ImpactEffect` fields:
- `EffectId` required entity effect asset id.
- `Radius` application radius around the projectile impact point.
- `ExcludeSource` optional bool, defaults to `true`.

`LingeringHazard` fields:
- `Radius` damage radius around the projectile impact point.
- `DurationSeconds` total linger time after impact.
- `TickIntervalSeconds` time between damage pulses.
- `DamagePerTick` damage applied on each pulse.
- `ExcludeSource` optional bool, defaults to `true`.
- `SourceTypeId` optional environment-source id used when the original shooter can no longer be resolved.
- `EffectId` optional entity effect asset id to reapply on each hazard pulse.

Behavior:
- Uses the projectile's `MuzzleVelocity` and `Gravity` to solve the high-angle lob when `TrajectoryMode` is `HIGH_ANGLE`.
- Uses a direct point-at-target pitch when `TrajectoryMode` is `DIRECT`.
- Uses the normal projectile spawn path after solving, so projectile asset offsets such as `VerticalCenterShot`, `HorizontalCenterShot`, `DepthShot`, and `PitchAdjustShot` still apply.
- If no valid arc exists and `FailIfNoSolution` is `true`, the interaction fails cleanly.
- Random-around-source targeting samples a uniform point in the authored radius band, which is useful for source-centered area denial barrages.
- `ImpactEffect` applies on projectile removal, which lets a single authored effect cover direct hits and explosion splashes.
- `LingeringHazard` damage is driven server-side from the projectile's final transform position when the projectile is removed.
- Player movement effects are handled by the base game; Tamework does not resync player `HorizontalSpeedMultiplier` values.

Example:
```json
{
  "Type": "TameworkLaunchProjectile",
  "ProjectileId": "Hydra_Rain_Ice_Ball",
  "TargetSlot": "CAETargetSlot",
  "TrajectoryMode": "DIRECT",
  "YawSpreadDegrees": 4.0,
  "PitchSpreadDegrees": 2.0
}
```

Area denial example:
```json
{
  "Type": "TameworkLaunchProjectile",
  "ProjectileId": "Hydra_Rain_Ice_Ball",
  "RandomAroundSourceMinRadius": 4.0,
  "RandomAroundSourceMaxRadius": 10.0,
  "RandomAroundSourceVerticalOffset": 0.0,
  "ImpactEffect": {
    "EffectId": "Chilled",
    "Radius": 5.0,
    "ExcludeSource": true
  },
  "LingeringHazard": {
    "Radius": 4.0,
    "DurationSeconds": 6.0,
    "TickIntervalSeconds": 1.0,
    "DamagePerTick": 5.0,
    "ExcludeSource": true,
    "EffectId": "Chilled"
  }
}
```

## Requirements
`Requires` has two buckets:
- `All`: every listed requirement set must pass.
- `Any`: at least one listed requirement set must pass.

Within each requirement type array, any one entry can satisfy that type.

### Basic booleans
- `LovedItems`
- `IsHarvestable`
- `IsMountable`
- `IsTamed`
- `IsNotTamed`
- `PlayerHandEmpty`
- `PlayerCrouching`
- `PlayerIsOwner`
- `HarvestAlarmReady`
- `HarvestInteractionContext`

### `ItemsInHand`
Fields:
- `Items`
- `ItemsParam`
- `Quantity`
- `Operator` (`AnyOf`, `NoneOf`)

### `ItemsInInventory`
Fields:
- `Items`
- `ItemsParam`
- `Quantity`

### `ItemsEquipped`
Fields:
- `Items`
- `ItemsParam`
- `Slots` (`Head`, `Chest`, `Hands`, `Legs`, `Armor`, `Equipped`, `Utility`, `Accessory`, `Accessories`)

### `Parameter`
Fields:
- `Name`
- `Operator` (`Equals`, `NotEquals`, `GreaterThan`, `GreaterThanOrEqual`, `LessThan`, `LessThanOrEqual`)
- `Match` (`Any`, `All`)
- `Value`

### `NpcHealthPercent`
Fields:
- `Operator`
- `Value` (`0-100` scale)

### `AlarmState`
Fields:
- `AlarmParam`
- `Name`
- `State` (`Unset`, `Active`, `Passed`)

### `NpcState`
Fields:
- `State`
- `SubState`

### `PlayerMovementState`
Field:
- `State` (`Crouching`, `Walking`, `Running`, `Sprinting`, `Idle`, `Mounting`, `Sleeping`)

### `InteractionContext`
Fields:
- `Context`
- `ContextParam`

## Effects
Common effect families:
- State/ownership: `SetTamed`, `SetOwner`, `SetState`, `SetRole`, `ModifyStats`
- Item operations: `RemoveItemsHand`, `AddItemsHand`, `RemoveItemsInventory`, `AddItemInventory`
- Presentation: `ShowFloatingText`, `ShowUiMessage`, `PlaySound`, `SpawnParticles`
- Utility: `DropItem`, `Mount`, `TriggerNpcHook`

`SpawnParticles` supports node/attachment targeting:
- `AttachTarget` (`Position`, `Entity`, `Node`)
- `AttachNode`
- `OffsetParam`
- `PlayerOnly`

## Channeled spawner capture item interaction

`TameworkCaptureChannel` coordinates a custom hold-to-capture flow with Hytale's native `Charging` interaction. It requires a targeted NPC and a held item backed by `TwSpawnerConfig`.

- `Phase: Begin` validates the empty spawner, target role/state, ownership, cooldown, and distance, then applies `Capture.ChannelAuraEffectId`. Health and required-effect gates are intentionally deferred until completion so channel feedback can begin before the target is capture-ready.
- `Phase: Cancel` removes the channel aura without capturing.
- `Phase: Complete` removes the aura, revalidates every capture requirement, and schedules the normal transactional spawner capture.

Use `Begin` before `Charging`, route the charge release branch (`0.0`) to `Cancel`, and route the desired duration (for example `3.0`) to `Complete`. Configure `BeamParticleSystem` on `Begin`; Tamework emits bounded world-space segments between the player and the locked target only while that server-tracked channel is active.

## Action usage in roles
```json
"Actions": [
  {
    "Type": "LockOnInteractionTarget",
    "TargetSlot": { "Compute": "MasterTargetSlot" }
  },
  { "Type": "TameworkInteract" }
]
```

Optional action overrides:
- `ConfigId`
- `LovedItems`
- `IsMountable`
- `IsHarvestable`
- `HarvestInteractionContext`
