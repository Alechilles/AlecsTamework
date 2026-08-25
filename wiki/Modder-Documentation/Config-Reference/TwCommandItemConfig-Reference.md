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
- `TargetHudContributors` and `HotswapHudContributors` are explicit arrays and
  replace the parent list, including when the child sets an empty array.
- HUD renderer and contributor fields inherit independently from the parent.
- `TwCommandItemConfig` is one of the item families refreshed by `/tw reloadconfig`.

## Top-Level Structure
```json
{
  "Enabled": true,
  "ItemIds": [],
  "Radius": -1,
  "MembershipMode": "LinkedOnly",
  "RosterStorage": "ItemMetadata",
  "UiRendererId": null,
  "UiContributors": [],
  "TargetHudRendererId": null,
  "TargetHudContributors": [],
  "HotswapHudRendererId": null,
  "HotswapHudContributors": [],
  "CommandFamilyId": null,
  "BondedRosterId": null,
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
- `RosterStorage`: `ItemMetadata` for the legacy/default per-item authority or
  `OwnerCommandFamily` for a durable owner/family roster, or
  `BondedCompanions` for the separate bonded profile-and-lease authority.
- `CommandFamilyId`: stable namespaced family shared by equivalent access
  items; required for `OwnerCommandFamily`.
- `UiRendererId`: optional namespaced Java command-menu renderer ID. Omit it or
  use `null` for the standard Tamework menu. The effective ID must have a live
  renderer registration through `TameworkApi.commandUi()` when the menu opens.
- `UiContributors`: ordered list of namespaced contributor requirements. Each
  entry has `Id` and `Required`. This explicit array replaces the parent list;
  an omitted array inherits it. A missing, incompatible, or failed required
  contributor causes standard-menu fallback. An optional contributor does not.
- `TargetHudRendererId`: optional namespaced renderer for the target HUD. IDs
  are normalized to lowercase. A blank or `null` value selects the standard
  Tamework target HUD. The `tamework:` namespace is reserved. When omitted,
  this field inherits from the parent.
- `TargetHudContributors`: ordered target-HUD contributor requirements. Each
  entry has `Id` and `Required`; IDs are normalized to lowercase, and the
  `tamework:` namespace and duplicate IDs are rejected. When omitted, the list
  inherits from the parent. An explicit list replaces it, including `[]`.
- `HotswapHudRendererId`: optional namespaced renderer for the equipped-item
  hotswap HUD. IDs are normalized to lowercase. A blank or `null` value selects
  the standard Tamework hotswap HUD. The `tamework:` namespace is reserved.
  When omitted, this field inherits from the parent independently of target HUD
  selection.
- `HotswapHudContributors`: ordered hotswap-HUD contributor requirements. Each
  entry has `Id` and `Required`; IDs are normalized to lowercase, and the
  `tamework:` namespace and duplicate IDs are rejected. When omitted, the list
  inherits from the parent. An explicit list replaces it, including `[]`.
- `BondedRosterId`: stable namespaced roster referenced by
  `BondedCompanions`; it must exist in the accepted bonded roster generation.
- `ProjectRosterToItemMetadata`: optionally writes a disposable item cache for
  presentation/compatibility. The cache never becomes roster authority.
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

With `RosterStorage: ItemMetadata`, linked membership remains on that command
item. With `OwnerCommandFamily`, membership and stable slots are durable for
the owner/family and equivalent access items see the same roster. Optional item
metadata is only a disposable projection. Both modes resolve canonical profile
IDs and read lifecycle status from replacement persistence; neither item cache
may invent death, Lost, captured, coop, stored, or provisioned state.

With `RosterStorage: BondedCompanions`, the command item is only an access,
panel, and live-command surface for `BondedRosterId`. Cards are keyed by stable
bonded profile ID and are loaded from the separate bonded database. The public
states are exactly `STORED`, `ACTIVE`, and `DEAD`; only an exact current
projection can receive normal NPC commands.

A bonded config must not declare `CommandFamilyId` or
`ProjectRosterToItemMetadata`, even as explicit `false`. It does not create
generic links, item-cache roster rows, relocation work, or generic lifecycle
aliases. Multiple policy assets may contribute different families to the same
`BondedRosterId`; all appear in the same panel while retaining family-specific
limits, timers, cooldowns, revival recipes, and action toggles.

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
- `UiRendererId` changes only the menu controller and layout.
  `UiContributors` adds isolated presentation, server actions, and custom
  flows that the renderer declares it can display. Tamework still owns
  snapshots, action authority, current-world dispatch, and fallback.
- `TargetHudRendererId` and `TargetHudContributors` apply only to target HUD
  composition. `HotswapHudRendererId` and `HotswapHudContributors` apply only
  to equipped-item hotswap HUD composition. Each surface defaults to the
  standard HUD when no renderer is selected, and each surface can be configured
  without changing the other.
- Shared relocation retry behavior and unlink-confirm policy come from [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference).
- `RequireOwner` can be left unset to inherit global linking-owner policy.
- `OwnerCommandFamily` requires `RequireOwner: true` and a non-blank
  `CommandFamilyId`.
- `BondedCompanions` requires a non-blank, currently defined
  `BondedRosterId`, rejects generic family/projection fields, and routes
  summon/store/revive actions by profile ID plus expected revision.

## Bonded roster example

```json
{
  "Parent": "TwCommandExample",
  "Enabled": true,
  "ItemIds": [ "Example_Bonded_Controller" ],
  "RosterStorage": "BondedCompanions",
  "BondedRosterId": "example:shared_roster",
  "MembershipMode": "LinkedOnly",
  "LinkEnabled": false,
  "LinkUseTogglesMembership": false,
  "RequireTamed": true,
  "RequireOwner": true,
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [
      "Tamed_Example_Large",
      "Tamed_Example_Small"
    ]
  }
}
```

The role allowlist controls live command recipients and presentation access;
the bonded roster family policies remain the lifecycle and capacity authority.

## Optional Example Is Not a Player Acquisition Flow

`Tamework_Command_Whistle_Example` and `TwCommandExample` are development and
configuration examples in the optional `Alec's Tamework! Examples` pack. The
example covers the HyDragon-relevant `Follow`, `Hold`, `Recall`, and
`AttackTarget` command path, but the pack includes no recipe or other polished
acquisition path for that item. Enable the pack and give it to an operator or
development workflow when needed.

A production downstream mod should provide its own item identity, localized
presentation, command config, and recipe or other explicit acquisition
mechanic. Treat the optional example as reference material, not as a finished
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
- Bonded roster and dependent command configs reload atomically. An invalid
  bonded reference leaves the prior coherent generation active.

## Related Pages
- [Command System and Linked Panel Guide](/mod/alecs-tamework/command-system-and-linked-panel-guide)
- [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)
- [TwBondedCompanionRosterConfig Reference](/mod/alecs-tamework/twbondedcompanionrosterconfig-reference)
- [Bonded Companion API Reference](/mod/alecs-tamework/bonded-companion-api-reference)



