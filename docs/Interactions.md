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
- Checks durable owner-population and matching group admission before changing
  ownership.
- Sets tamed + owner, consumes the held item, and performs an optional role
  swap only after the live mutation succeeds.

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
- Roles can use transformed-player flight by setting `MountMode` to `TameworkAvatarFlight` and `AvatarFlightConfig` to an enabled config asset id. Prompt and action eligibility both reject missing configs, dead participants, and existing native/ride/glide/avatar mount state.
- NPC-backed avatar flight hides and parks the same companion entity until dismount. Press F to dismount immediately, including while airborne; airborne normal dismount restores the companion at the current flight position. Grounded back+crouch remains an alternate hold input controlled by `Mounting.RequireGroundedDismount`. Clean disconnects queue normal lifecycle cleanup, missing riders restore through the source watchdog, and server restarts invalidate persisted mount pairs before either side can resume. Forced cleanup also covers death, world transfer, missing sources, disabled configs, and orphaned sessions.
- See [Avatar Flight](Avatar-Flight.md) for setup, controls, lifecycle behavior, and config fields.

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
- When a manually selected pair is found: applies the parent cooldown, moves the pair together,
  shows hearts, then spawns offspring 2.2 seconds later. Pair movement can add up to five seconds.
- `Cooldowns.MinDelaySeconds` and `MaxDelaySeconds` define random cooldown jitter.
  They do not delay the current offspring spawn.
- Fertility intentionally resolves a litter of zero through four offspring. Tamework multiplies the two resolved parent fertility factors, clamps the expected litter to four, guarantees the whole-number portion, and uses one fractional roll for at most one additional child. Similar-looking siblings from one admitted litter are not duplicate callbacks.
- `Pairing.MaxNearbySameType` is checked against nearby live NPCs. Passive
  sweeps reserve the maximum possible litter, and delayed births recheck live
  headroom immediately before spawning.
- A parent can belong to only one queued manual or passive pairing at a time;
  the admission is released when the delayed birth completes or is canceled.
- Delayed births refresh both parent owners before spawning. When
  `Pairing.RequireSameOwner` is enabled, the birth is canceled if both parents
  no longer have the same known owner.
- Direct SimpleClaims rules may require a claim and apply per-chunk or
  total-claim breeding limits.
- Pairing can require the same role, require different adult roles in one lifecycle family, allow any adult in one lifecycle family, or explicitly allow any role through `TwBreedingConfig.Pairing.RoleCompatibility`.
- If `TwBreedingConfig.Gender.Enabled` and `RequireDifferentGender` are enabled, partner selection also requires one male and one female companion.
- Offspring flow supports baby-role preference, persisted weighted adult-role selection, life-stage initialization, trait/attachment inheritance, and growth timing. `TwBreedingConfig.Inheritance.AttachmentInheritance.ExcludedSets` can leave equipment or other non-genetic attachment sets at the child's model-generated default.
- World-time deadlines are signed. Negative timestamps are valid; only `0` means unset.

## Ownership rules

`SetOwner` and Tame check durable owner-population admission when adding a
non-null owner. The configured global/per-world cap counts canonical owned
profiles, and positive acquisitions reserve capacity inside the same operation.
This is independent from SimpleClaims placement and does not create a
feature-local provisioning or population journal.

`TameworkOwnerComponent` is the canonical live authorization source for
ownership mutation and command access. A clear invalidates command-tool links
and clears name ownership; a transfer invalidates the prior owner's links and
retargets retained name metadata.

`SetOwner` with `Source: Custom` requires a syntactically valid UUID in `Uuid`. `Name` is optional display metadata, not an identity; a blank or malformed UUID rejects the effect instead of being interpreted as an ownership clear.

SimpleClaims placement does not gate Tame, SetOwner, spawn, Recall, coop
release, death restoration, or Lost restoration. Its Tamework breeding limits
apply only to breeding.

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

### `TameworkCullNpc`

`TameworkCullNpc` is a terminal item interaction that kills one targeted NPC
through Tamework's cull path. It applies fatal command damage. The NPC's usual
death effects and drops occur unless its managed activity profile maps the
role's family through `Activities.CullDropLists`. A resolved domestic table is
rolled once and replaces the normal death drops.

The interaction has no matching `Tw*Config` asset. Put it on a consumer mod's
item and use that item's native interaction chain for range, cooldown,
durability, animation, and optional hold confirmation.

```json
{
  "Type": "TameworkCullNpc",
  "RequireOwner": true,
  "RequireTamed": true
}
```

Both fields default to `true`. The interaction rejects missing or non-NPC
targets, targets that do not meet the selected owner/tame policy, and bonded
companion projections. It clears ordinary command links before the death path
and removes the NPC from eligible generic command tools in the hotbar. After a
successful managed cull, Activity V2 emits `tamework:cull_success` with the
owner, companion, family, mapped activity ID, and rolled item quantities.

For a butcher-style item, put this interaction in a named interaction asset,
then reference that asset on the successful completion branch of the item's
native `Charging` interaction. For example, use
`"0.8": "My_Butchers_Knife_Cull_Complete"` in `Next`, and define
`My_Butchers_Knife_Cull_Complete` as `{ "Type": "TameworkCullNpc" }` in a
separate interaction file. Tamework supplies no standalone culling item or
confirmation UI.

### `TameworkCaptureChannel`

Runs one phase of a server-authoritative spawner capture channel. Use it as the first step of a native `Charging` interaction, with `Begin`, `Cancel`, and `Complete` phases.

Optional `Begin` fields:

- `BeamParticleSystem`: world particle system repeatedly emitted from a view-relative right-hand item anchor to the initially targeted NPC.
- `BeamNativeLength`: authored forward length of that particle system. Tamework scales each short-lived segment to stop at the target. Defaults to `50`.
- `BeamNativeDurationSeconds`: authored travel duration corresponding to `BeamNativeLength`. Fixed-size traveling particles use both values to derive their target-distance lifetime. Defaults to `0.5`.
- `ScaleBeamToTarget`: when `true` (default), uniformly scales the particle system to the target distance. Set to `false` for fixed-size traveling particles; Tamework instead scales the instance lifetime relative to `BeamNativeLength` so it ends at the target.
- `BeamFromTarget`: when `true`, emits each particle at the locked NPC anchor and aims it toward the player's live held-item anchor. Defaults to `false` for the original item-to-target direction.
- `HomingProjectileEnabled`: when `true`, replaces the rigid world-particle stream with independently homing visual entities. Defaults to `false` for compatibility.
- `HomingProjectileModelId`: model asset containing the mote's attached model particle.
- `HomingProjectileSpawnIntervalSeconds`: seconds between motes. Defaults to `0.12`.
- `HomingProjectileSpeed`: travel speed in blocks per second. Defaults to `8`.
- `HomingProjectileTurnRateDegreesPerSecond`: optional turning limit. Use `0` for direct per-tick retargeting to the held stone.
- `HomingProjectileArrivalRadius`: removal distance around the live held-item anchor. Defaults to `0.18`.
- `HomingProjectileLifetimeSeconds`: hard non-persistent mote lifetime. Defaults to `2`.
- `HomingProjectileMaxConcurrent`: per-capture active mote cap. Defaults to `16` and is clamped to `64`.
- `ChannelDurationSeconds`: maximum server-side visual session lifetime. Match this to the charging threshold. Defaults to `3`.

Optional `Complete` field:

- `CaptureBurstParticleSystem`: one-shot world particle system emitted at the locked target only after the transactional capture applies successfully.

The initial target is locked for the session. When homing is enabled, each mote starts at the locked NPC's body anchor and recomputes the player's view-relative held-item anchor every server tick. Every mote carries the capture generation, is non-serialized, and is removed on arrival, TTL, cancel, completion, disconnect, invalid target, world change, or session replacement. If the configured homing model cannot be loaded, Tamework logs once and uses `BeamParticleSystem` as the compatibility fallback without failing gameplay capture.

For a left-click channel, place the root under the item's `Interactions.Primary` key. `Use` is the F interaction and can conflict with NPC interaction options.

### `TameworkLaunchHomingVisualProjectile`

Launches one harmless, non-serialized model entity whose attached particle follows a live entity anchor. It does not use Hytale's combat projectile, collision, damage, hit, or explosion paths.

Fields:

- `ModelId`: required model asset containing the attached particle definition.
- `Source`: optional `USER`, `OWNER`, or `TARGET`; defaults to `USER`.
- `Target`: optional `USER`, `OWNER`, or `TARGET`; defaults to `TARGET`.
- `SourceAnchor`: optional `ROOT`, `BODY`, or `HELD_ITEM`; defaults to `BODY` and is sampled once at launch.
- `TargetAnchor`: optional `ROOT`, `BODY`, or `HELD_ITEM`; defaults to `BODY` and is recomputed every server tick.
- `Speed`: positive travel speed in blocks per second; defaults to `8`.
- `TurnRateDegreesPerSecond`: optional turn limit. `0` directly retargets every tick and is the most reliable setting for moving endpoints.
- `ArrivalRadius`: positive distance at which the visual entity is removed; defaults to `0.18`.
- `LifetimeSeconds`: positive hard lifetime; defaults to `2`.

```json
{
  "Type": "TameworkLaunchHomingVisualProjectile",
  "ModelId": "Example_Capture_Mote",
  "Source": "TARGET",
  "Target": "USER",
  "SourceAnchor": "BODY",
  "TargetAnchor": "HELD_ITEM",
  "Speed": 8.0,
  "TurnRateDegreesPerSecond": 0.0,
  "ArrivalRadius": 0.18,
  "LifetimeSeconds": 2.0
}
```

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
- Independent player movement effects are handled by the base game; Tamework does not resync player `HorizontalSpeedMultiplier` values. Companion movement configuration can still scale a rider's native `BaseSpeed` while mounted on that companion.

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

`SetRole` accepts `Role` (or `RoleParam`) and optional `ChangeAppearance`.
`ChangeAppearance` defaults to `false`, preserving legacy role swaps; set it to
`true` only when the target role must update the NPC's visible appearance. Role
configs that offer costed role-change entries must exclude self-targeting entries
so an NPC cannot pay a cost to swap to its current role.

`SpawnParticles` supports node/attachment targeting:
- `AttachTarget` (`Position`, `Entity`, `Node`)
- `AttachNode`
- `OffsetParam`
- `PlayerOnly`

## Channeled spawner capture item interaction

`TameworkCaptureChannel` coordinates a custom hold-to-capture flow with Hytale's native `Charging` interaction. It requires a targeted NPC and a held item backed by `TwSpawnerConfig`.

- `Phase: Begin` validates the empty spawner, target role/state, ownership, cooldown, and distance, then applies `Capture.ChannelAuraEffectId`. Health and required-effect gates are intentionally deferred until completion so channel feedback can begin before the target is capture-ready.
- `Phase: Cancel` removes the channel aura without capturing.
- `Phase: Complete` removes the aura, revalidates every capture requirement, and schedules the normal transactional spawner capture. A missing required effect produces player-facing feedback; `Tw_Status_Tranquilized` uses a specific tranquilization warning. If configured, `CaptureBurstParticleSystem` plays only after that capture applies successfully.

Use `Begin` before `Charging`, route the charge release branch (`0.0`) to `Cancel`, and route the desired duration (for example `3.0`) to `Complete`. Configure `BeamParticleSystem` on `Begin` and `CaptureBurstParticleSystem` on `Complete`; Tamework emits bounded world-space segments between the player and the locked target only while that server-tracked channel is active.

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
