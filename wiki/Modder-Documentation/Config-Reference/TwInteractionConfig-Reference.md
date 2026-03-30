---
title: "TwInteractionConfig Reference"
order: 16
published: true
draft: false
---
# TwInteractionConfig Reference

Parent: [Config Reference Index](/mod/alecs-tamework/config-reference-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

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
- `TwInteractionConfig` is not reloaded by `/tw reloadconfig`; it refreshes through normal asset load/remove flow.

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

Behavior notes:
- `CooldownSeconds` overrides `Cooldowns.InteractionSeconds` for that entry.
- `PromptHint` is the translation key used by `TameworkInteractPrompt`.
- `ShowPrompt` only controls prompt display. It does not disable execution by itself.

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

When `Cycle` is omitted, Tamework falls back to the default mode cycle.

### `Breed`
Fields:
- `RequireTamed`
- `MinHappiness`
- `FertilityBonus`

Use this preset when an interaction should hand off into the breeding runtime rather than only changing state.

### `Custom`
No preset-specific fields. Use `Requires` and `Effects` directly.

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
- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)

