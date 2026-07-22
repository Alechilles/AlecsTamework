---
title: "TwCommandItemConfig Reference"
order: 19
published: true
draft: false
---
# TwCommandItemConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwCommandItemConfig` binds command-tool behavior to one or more items. It controls recipient selection, linking rules, command cooldowns, role filtering, radial command definitions, linked-panel behavior, and the step sequence executed for each command.

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Items/Commands/*.json`
- Scope: item-scoped
- Resolution key: `ItemIds`
- Runtime reload: `/tw reloadconfig` reloads command-item assets into the item feature registry

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level object sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays and maps replace the parent value.
- `ItemIds` and `CommandList` are explicit arrays and replace the parent list.
- `TwCommandItemConfig` is one of the item families refreshed by `/tw reloadconfig`.

## Top-Level Structure
```json
{
  "Enabled": true,
  "ItemIds": [],
  "Radius": -1,
  "MembershipMode": "LinkedOnly",
  "CommandFamilyId": null,
  "RosterStorage": "ItemMetadata",
  "ProjectRosterToItemMetadata": true,
  "LinkEnabled": true,
  "LinkUseTogglesMembership": true,
  "RequireTamed": true,
  "RequireOwner": true,
  "MaxTargets": 25,
  "MaxActive": 0,
  "CooldownSeconds": 2,
  "RequireLineOfSight": false,
  "AllowedRoles": { "...": "..." },
  "CommandList": [
    { "...": "..." }
  ]
}
```

## Top-Level Field Reference
- `Enabled`: disables the config when `false`.
- `ItemIds`: item ids that resolve this config.
- `Radius`: recipient search radius. Use `-1` for unrestricted radius.
- `MembershipMode`: target-selection mode.
- `CommandFamilyId`: stable owner-scoped family shared by equivalent command access items. Required for `OwnerCommandFamily`.
- `RosterStorage`: `ItemMetadata` (compatibility default) or `OwnerCommandFamily` (durable canonical roster).
- `ProjectRosterToItemMetadata`: optionally writes a disposable item cache. Copied, stale, or missing metadata never creates or removes canonical membership.
- `LinkEnabled`: allows persistent link and unlink actions for the tool.
- `LinkUseTogglesMembership`: makes the link action toggle whether the NPC is in the active membership set.
- `RequireTamed`: requires targets to be tamed.
- `RequireOwner`: requires the using player to own the target. If omitted, `/tw settings` provides the global linking owner requirement.
- `MaxTargets`: max recipients per command dispatch.
- `MaxActive`: max linked NPCs that can be marked active. `0` means unlimited.
- `CooldownSeconds`: item cooldown after command use.
- `RequireLineOfSight`: requires line of sight for selection or targeting logic that uses it.
- `AllowedRoles`: role filter for commandable targets.
- `CommandList`: authored commands shown in the radial and executed at runtime.

## Membership Modes
Accepted `MembershipMode` values:
- `LinkedOnly`: only linked companions are valid recipients.
- `OwnerScope`: owned companions in scope can be targeted even if they are not linked.
- `MasterTarget`: target only the current resolved master target.
- `LinkedOrMasterTarget`: linked companions plus the current master target.

For `RosterStorage: OwnerCommandFamily`, every configured `ItemIds` entry is an equivalent access key to the same `(owner UUID, command family ID)` roster. The panel and command runtime reconstruct membership from SQLite/profile state; the physical item is not the authority.

## `AllowedRoles`
Accepted `Mode` values:
- `AllowAll`
- `Allowlist`
- `Denylist`

Fields:
- `Mode`
- `Allowlist`
- `Denylist`

## `CommandList` Entry Schema
Each command entry supports:
- `Id`: stable command id.
- `DisplayName`: UI label.
- `Icon`: optional icon asset.
- `Default`: marks the default selected command.
- `Feedback`: optional command feedback bundle.
- `ModeMapping`: optional state-to-mode mapping for the radial and linked panel UI.
- `Steps`: ordered command steps executed for each resolved recipient.

### `Feedback`
- `ChatMessage`
- `HudMessage`
- `SoundEvent`
- `ParticleSystem`
- `ParticleOffset`

### `ModeMapping`
Use this when the runtime should treat a command as the UI representation of a specific companion mode.

Fields:
- `State`
- `SubState`
- `Message`

## Step Schema
Every step supports:
- `Type`
- `FailurePolicy`
- `Optional`

Accepted `FailurePolicy` values:
- `Continue`
- `AbortCommandForNpc`
- `AbortAll`

### `SetState`
Fields:
- `State`
- `SubState`

### `SetTarget`
Fields:
- `TargetSlot`
- `Source`

Accepted `Source` values:
- `CrosshairTarget`
- `LastAttackTarget`
- `OwnerPlayer`
- `StoredTarget`

### `ClearTarget`
Fields:
- `TargetSlot`

### `ClearCombat`
Fields:
- `State`
- `SubState`
- `TargetSlots`
- `AssignOwnerAsMasterTarget`

### `MoveToPosition`
Fields:
- `Source`

Accepted `Source` values:
- `RaycastHit`
- `OwnerPosition`
- `StoredHome`

### `StoreHome`
Fields:
- `Source`

Accepted `Source` values:
- `RaycastHit`
- `OwnerPosition`

### `TriggerHook`
Fields:
- `HookId`
- `Payload`

`Payload` is a string-to-string map passed through the hook runtime.

## Runtime Coupling Notes
- The item interaction is `TameworkCommand`.
- Left-click typically runs the current command; right-click commonly uses `CommandId: OpenSelectionMenu` to open the radial.
- The linked panel uses this config’s command list and recipient rules but also depends on runtime services, linked companion records, and effective companion policy from [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference).
- Shared relocation retry behavior and unlink-confirm policy come from [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference).
- `RequireOwner` can be left unset to inherit global linking-owner policy.

## Bundled Example Is Not a Player Acquisition Flow

`Tamework_Command_Whistle_Example` and `TwCommandExample` are development and
configuration examples. The example covers the HyDragon-relevant `Follow`,
`Hold`, `Recall`, and `AttackTarget` command path, but Tamework ships no recipe
or other polished acquisition path for that item. It must be given by an
operator or development workflow.

A production downstream mod should provide its own item identity, localized
presentation, command config, and recipe or other explicit acquisition
mechanic. Treat the bundled example as reference material, not as a finished
player-facing tool.

## Minimal Example
```json
{
  "Enabled": true,
  "ItemIds": [
    "Item_Command_Whistle"
  ],
  "MembershipMode": "LinkedOnly",
  "RequireTamed": true,
  "RequireOwner": true,
  "AllowedRoles": {
    "Mode": "AllowAll"
  },
  "CommandList": [
    {
      "Id": "Follow",
      "DisplayName": "Follow",
      "Default": true,
      "Steps": [
        {
          "Type": "SetState",
          "State": "Follow"
        },
        {
          "Type": "SetTarget",
          "TargetSlot": "MasterTarget",
          "Source": "OwnerPlayer"
        }
      ]
    }
  ]
}
```

## Common Pattern Example
```json
{
  "Enabled": true,
  "ItemIds": [
    "Tamework_Command_Whistle_Example"
  ],
  "Radius": -1,
  "MembershipMode": "LinkedOnly",
  "LinkEnabled": true,
  "LinkUseTogglesMembership": true,
  "RequireTamed": true,
  "RequireOwner": true,
  "MaxTargets": 25,
  "MaxActive": 0,
  "CooldownSeconds": 2,
  "RequireLineOfSight": false,
  "AllowedRoles": {
    "Mode": "AllowAll"
  },
  "CommandList": [
    {
      "Id": "Follow",
      "DisplayName": "Follow",
      "Default": true,
      "Feedback": {
        "HudMessage": "Follow: {count}",
        "SoundEvent": "SFX_Creative_Play_Selection_Widget",
        "ParticleSystem": "Follow"
      },
      "ModeMapping": {
        "State": "Follow",
        "Message": "Following"
      },
      "Steps": [
        {
          "Type": "ClearTarget",
          "TargetSlot": "LockedTarget"
        },
        {
          "Type": "SetState",
          "State": "Follow"
        },
        {
          "Type": "SetTarget",
          "TargetSlot": "MasterTarget",
          "Source": "OwnerPlayer"
        }
      ]
    },
    {
      "Id": "SetHome",
      "DisplayName": "Set Home",
      "Feedback": {
        "ChatMessage": "Home set for linked NPC(s).",
        "HudMessage": "Home Set",
        "SoundEvent": "SFX_Creative_Play_Selection_Widget",
        "ParticleSystem": "Poof_Small"
      },
      "Steps": [
        {
          "Type": "StoreHome",
          "Source": "RaycastHit",
          "FailurePolicy": "AbortAll"
        }
      ]
    }
  ]
}
```

## Gotchas
- `CommandList` is ordered and explicit. A child asset that authors it replaces the entire parent list.
- `MaxActive: 0` means unlimited, not zero active companions.
- `ModeMapping` is UI-facing metadata. It does not replace the `Steps` that actually perform the command.
- `/tw reloadconfig` is required after editing command-item configs during development.

## Related Pages
- [Command System and Linked Panel Guide](/mod/alecs-tamework/command-system-and-linked-panel-guide)
- [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)



