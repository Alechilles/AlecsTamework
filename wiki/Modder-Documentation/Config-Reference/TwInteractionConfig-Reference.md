---
title: "TwInteractionConfig Reference"
order: 16
published: true
draft: false
---
# TwInteractionConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwInteractionConfig` is the optimized interaction family used by `TameworkInteract`. It replaces large authored instruction graphs with an ordered interaction list driven by presets, requirements, and effects.

Use it when you want to author:
- taming, feeding, harvesting, mounting, mode-cycle, or breed interactions
- fully custom interaction gates with reusable effect bundles
- prompt behavior and real-time interaction cooldowns
- per-role interactions without large vanilla instruction trees

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Interactions/*.json`
- Scope: role-scoped
- Resolution: highest enabled `Priority` whose `RoleIds` contains the NPC role

### Resolution Precedence
When `TameworkInteract` runs, Tamework resolves the config in this order:
1. explicit `ConfigId` on the `TameworkInteract` action
2. role param named by `TwGlobalConfig.InteractionDefaults.InteractionConfigParam`
3. highest-priority enabled config whose `RoleIds` matches the NPC role

When deterministic selection matters and multiple configs could apply, set `ConfigId`.

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level object sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays replace the parent value.
- `Interactions` is an explicit ordered array and replaces the parent list when authored in a child asset.
- `TwInteractionConfig` is not reloaded by `/tw config reload`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "Enabled": true,
  "Priority": 0,
  "RoleIds": [],
  "Interactions": [
    { "...": "..." }
  ],
  "Cooldowns": {
    "InteractionSeconds": 2
  }
}
```

## Top-Level Field Reference
- `Enabled`
- `Priority`
- `RoleIds`
- `Interactions`: ordered interaction list. First enabled entry whose requirements pass wins.
- `Cooldowns.InteractionSeconds`: default cooldown for entries that do not set `CooldownSeconds`

## Base Entry Fields
Every interaction entry supports:
- `Type`
- `Enabled`
- `CooldownSeconds`
- `PromptHint`
- `ShowPrompt`
- `Requires`
- `Effects`
- `Card`

Behavior notes:
- `CooldownSeconds` overrides `Cooldowns.InteractionSeconds` for that entry.
- `PromptHint` is the translation key used by `TameworkInteractPrompt`.
- `ShowPrompt` only controls prompt display. It does not disable execution by itself.

### `Card`
Puts the entry on the companion card as a button. F on an owned companion opens the card when the player's F
behaviour is Card (the server default); the card opens from the `ModeCycle` entry's position, so an entry above it that
still runs on F wins first.

Fields (all optional):
- `Label`: button label, a `server.lang` key or raw text. Default: Tamework's label for the entry type (Ride, Feed,
  Breed, Harvest, Tame, Interact). Tamework also provides `tamework.ui.companionCard.action.pet`.
- `Icon`: button glyph, a white 64x64 texture path under `Common/UI/Custom`. Default: Tamework's glyph for the type.
- `Order`: sort order among the card's buttons, lowest first. Ties keep authored order. Default `0`.
- `OnInteract`: `false` keeps the entry off F for players whose F opens the card, so F reaches the card instead.
  Players who chose the mode cycle still trigger it with F. Default `true`.

A card button runs the entry through the same requirements, effects and cooldown as F, with one difference: crouch,
movement-state and empty-hand requirements (`RequireCrouching`, `PlayerCrouching`, `PlayerMovementState`,
`PlayerHandEmpty`) do not apply, because they only choose which entry F triggers. Every other requirement still
applies; a button whose requirements fail is greyed out. `"Card": {}` is enough for a default button.

```json
{ "Type": "Mount", "RequireCrouching": true, "Card": { "Order": 10 } },
{ "Type": "Custom", "PromptHint": "server.interactionHints.pet",
  "Card": { "Label": "tamework.ui.companionCard.action.pet", "Order": 20, "OnInteract": false } }
```

## Preset Interaction Types
### `Tame`
Fields:
- `UseLovedItems`
- `ItemsInHand`
- `ItemsParam`
- `Role`
- `RoleParam`

Use this preset to tame an NPC, assign ownership, optionally consume a matching item, and optionally swap the NPC role.

### `Feed`
Fields:
- `UseLovedItems`
- `ItemsInHand`
- `Heal`
- `ItemsParam`

`ItemsInHand` for this preset uses feed-item entries shaped like:
- `Item`
- `Heal`

Use this preset to heal, consume food, apply happiness gain, and optionally trigger manual needs refill.

### `Harvest`
Fields:
- `RequireTamed`
- `RequireHarvestable`
- `RequireHarvestAlarmReady`
- `RequireHarvestInteractionContext`

Use this preset to gate harvesting against the shared harvest params and alarm names from `TwGlobalConfig.InteractionDefaults`.

### `Mount`
Fields:
- `RequireTamed`
- `RequireOwner`
- `RequireMountable`
- `RequireCrouching`

Use this preset for rideable NPCs that should require ownership, crouching, or a mountable flag.

### `ModeCycle`
Fields:
- `RequireTamed`
- `RequireOwner`
- `ShowFloatingText`
- `ShowUiMessage`
- `Cycle`

Each `Cycle` entry supports:
- `State`
- `SubState`
- `Message`
- `Label`: companion card label, a `server.lang` key or raw text. Default: Tamework's label for Follow, Hold, Idle,
  Defend, Aggressive and Sleep, otherwise the state name.
- `Icon`: companion card glyph texture path. Default: Tamework's mode glyph for those states.

On the companion card, each valid step is a mode button and the current one is highlighted. A mode button applies
that step directly, with the entry's requirements, message and effects.

When `Cycle` is omitted, Tamework falls back to the default mode cycle.

### `Breed`
Fields:
- `RequireTamed`
- `MinHappiness`
- `FertilityBonus`

Use this preset when an interaction should hand off into the breeding runtime rather than only changing state.

### `Custom`
No preset-specific fields. Use `Requires` and `Effects` directly.

### `TameworkLaunchProjectile`
Custom projectile interaction with arc-solve, target-slot fallback, spread, impact effects, and optional lingering hazards.

Fields:
- `ProjectileId` (required)
- `Target` (optional: `USER`, `OWNER`, `TARGET`; default `TARGET`)
- `TargetSlot` (optional; preferred NPC marked target slot before `Target` fallback)
- `TargetGroundOffset` (optional; entity feet plus this Y offset, replacing eye-height aiming; no terrain projection)
- `LandingMarkerParticleSystemId` (optional; particle emitted once at the frozen solver target after successful launch)
- `ImpactSpawnNpcRole` (optional; one NPC role spawned at the final projectile position on normal removal, including expiry)
- `YawSpreadDegrees` (optional)
- `PitchSpreadDegrees` (optional)
- `FailIfNoSolution` (optional; default `true`)
- `TrajectoryMode` (optional: `HighAngle`, `Direct`; default `HighAngle`)
- `RandomAroundSourceMinRadius` (optional)
- `RandomAroundSourceMaxRadius` (optional)
- `RandomAroundSourceVerticalOffset` (optional)
- `ImpactEffect` (optional nested block)
- `LingeringHazard` (optional nested block)

`ImpactEffect` fields:
- `EffectId`
- `Radius`
- `ExcludeSource` (optional; default `true`)

`LingeringHazard` fields:
- `Radius`
- `DurationSeconds`
- `TickIntervalSeconds`
- `DamagePerTick`
- `ExcludeSource` (optional; default `true`)
- `SourceTypeId` (optional)
- `EffectId` (optional)

Use this when you need combat-oriented lobs, source-centered barrage patterns, impact debuffs, or hazard zones that continue after projectile death.

The ground offset applies only to entity targets. Look and random targets keep their existing positions. Marker lifetime comes from the particle asset; spread and projectile shot offsets can move the actual impact away from the marker. Hatch spawns also happen on lifetime expiry, but not world unload. The spawned role owns movement, lifetime, and despawn behavior.

## Requirement Schema
`Requires` is split into two buckets:
- `All`: every authored requirement in the bucket must pass
- `Any`: at least one authored requirement in the bucket must pass

Within array-based requirement types, any one entry can satisfy that requirement type.

### Boolean Requirement Flags
Set these to `true` when you want the gate:
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
- `ItemsParam`
- `Items`
- `Quantity`
- `Operator`

Accepted `Operator` values:
- `AnyOf`
- `NoneOf`

### `ItemsInInventory`
Fields:
- `ItemsParam`
- `Items`
- `Quantity`

### `ItemsEquipped`
Fields:
- `ItemsParam`
- `Items`
- `Slots`

Accepted `Slots` values:
- `Head`
- `Chest`
- `Hands`
- `Legs`
- `Armor`
- `Equipped`
- `Utility`
- `Accessory`
- `Accessories`

### `NpcHealthPercent`
Fields:
- `Operator`
- `Value`

`Value` uses a `0-100` percent scale.

### `Parameter`
Fields:
- `Name`
- `Operator`
- `Match`
- `Value`

Accepted `Operator` values:
- `Equals`
- `NotEquals`
- `GreaterThan`
- `GreaterThanOrEqual`
- `LessThan`
- `LessThanOrEqual`

Accepted `Match` values:
- `Any`
- `All`

Authoring note:
- The JSON key is `Value`.
- It accepts a single string or an array of strings.

### `AlarmState`
Fields:
- `AlarmParam`
- `Name`
- `State`

Common `State` values:
- `Unset`
- `Active`
- `Passed`

### `NpcState`
Fields:
- `State`
- `SubState`

### `PlayerMovementState`
Fields:
- `State`

Accepted values:
- `Crouching`
- `Walking`
- `Running`
- `Sprinting`
- `Idle`
- `Mounting`
- `Sleeping`

### `InteractionContext`
Fields:
- `Context`
- `ContextParam`

## Effect Schema
### `SetTamed`
Fields:
- `Value`

### `SetOwner`
Fields:
- `Source`
- `Uuid`
- `Name`

Accepted `Source` values:
- `Player`
- `None`
- `Custom`

For `Source: Custom`, `Uuid` must contain a valid UUID. `Name` is optional display metadata and cannot identify an owner by itself. A blank or malformed `Uuid` rejects the effect; use `Source: None` for an intentional ownership clear.

### `ModifyStats`
Fields:
- `Stats`

Each `Stats` entry supports:
- `StatId`
- `Amount`

### `SetState`
Fields:
- `State`
- `SubState`

### `SetRole`
Fields:
- `Role`
- `RoleParam`

### `RemoveItemsHand`
Fields:
- `ItemsParam`
- `Items`
- `Quantity`

### `AddItemsHand`
Fields:
- `ItemsParam`
- `Items`

Each `Items` entry uses:
- `Item`
- `Quantity`

### `RemoveItemsInventory`
Fields:
- `ItemsParam`
- `Items`

Each `Items` entry uses:
- `Item`
- `Quantity`

### `AddItemInventory`
Fields:
- `ItemsParam`
- `Items`

Each `Items` entry uses:
- `Item`
- `Quantity`

### `Mount`
This is a boolean effect. Use:
- `"Mount": true`

### `PlaySound`
Fields:
- `SoundEvent`
- `Volume`
- `Pitch`
- `Offset`
- `PlayerOnly`

### `SpawnParticles`
Fields:
- `ParticleSystem`
- `ParticleSystemParam`
- `Offset`
- `OffsetParam`
- `AttachTarget`
- `AttachNode`
- `Color`
- `PlayerOnly`

Accepted `AttachTarget` values:
- `Position`
- `Entity`
- `Node`

### `DropItem`
Fields:
- `Item`
- `DropList`
- `QuantityMin`
- `QuantityMax`
- `ThrowSpeed`

### `TriggerNpcHook`
Fields:
- `HookId`
- `PlayerOnly`
- `Consume`

This is the bridge into `TameworkHook`.

### `ShowFloatingText`
Fields:
- `Message`

### `ShowUiMessage`
Fields:
- `Message`

## Prompts and Cooldowns
Prompt behavior depends on `TameworkInteractPrompt`.

Useful shipped prompt keys:
- `server.interactionHints.generic`
- `server.interactionHints.tame`
- `server.interactionHints.feed`
- `server.interactionHints.harvest`
- `server.interactionHints.harvestContext`
- `server.interactionHints.mount`
- `server.interactionHints.modeCycle`
- `server.interactionHints.breed`
- `server.interactionHints.custom`

Cooldown behavior:
- cooldowns are real-time seconds
- entry `CooldownSeconds` overrides config default cooldown
- alarm id format is `<InteractionCooldownAlarmPrefix>_<ConfigId>_<index>`
- `InteractionCooldownAlarmPrefix` comes from `TwGlobalConfig.InteractionDefaults`

## Minimal Example
```json
{
  "Enabled": true,
  "RoleIds": [
    "Mob_Tamework_Example_Simple"
  ],
  "Interactions": [
    {
      "Type": "Tame"
    },
    {
      "Type": "Feed",
      "Heal": 20
    }
  ]
}
```

## Common Pattern Example
```json
{
  "Enabled": true,
  "RoleIds": [
    "Mob_Tamework_Example"
  ],
  "Cooldowns": {
    "InteractionSeconds": 2
  },
  "Interactions": [
    {
      "Type": "Tame",
      "ItemsParam": "TestTameItemsParam",
      "Effects": {
        "ShowFloatingText": {
          "Message": "Tamed"
        }
      }
    },
    {
      "Type": "Feed",
      "UseLovedItems": false,
      "Heal": 10,
      "ItemsParam": "TestFeedItemsParam"
    },
    {
      "Type": "Breed",
      "MinHappiness": 70.0,
      "Requires": {
        "All": {
          "PlayerIsOwner": true,
          "PlayerCrouching": true
        }
      },
      "Effects": {
        "ShowFloatingText": {
          "Message": "Breeding Ready"
        }
      }
    },
    {
      "Type": "ModeCycle",
      "ShowFloatingText": true,
      "Cycle": [
        {
          "State": "Idle",
          "Message": "Following"
        },
        {
          "State": "Hold",
          "Message": "Staying"
        },
        {
          "State": "Defend",
          "Message": "Defending"
        }
      ]
    }
  ]
}
```

## Gotchas
- `Interactions` is ordered. The first enabled matching entry wins.
- Explicit `Interactions` arrays replace the parent list. There is no append merge.
- Use `ConfigId` when multiple interaction configs could match the same role and deterministic selection matters.
- The JSON key for parameter comparisons is `Value`, even though the runtime stores it as a values array.
- `TriggerNpcHook` only stages the hook payload. A compatible `TameworkHook` flow still has to consume it.

## Related Pages
- [Interaction Paths and Role Wiring](/mod/alecs-tamework/interaction-paths-and-role-wiring)
- [Projectile Combat and Hazard Interactions Guide](/mod/alecs-tamework/projectile-combat-and-hazard-interactions-guide)
- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)



