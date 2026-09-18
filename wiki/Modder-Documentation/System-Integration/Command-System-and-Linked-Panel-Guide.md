---
title: "Command System and Linked Panel Guide"
order: 8
published: true
draft: false
---
# Command System and Linked Panel Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

This guide covers how to ship a command tool successfully. Use it for wiring and decision-making. Use [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference) for the field-by-field schema.

## Implementation Flow
1. Create a `TwCommandItemConfig` in `Server/Tamework/Items/Commands/`.
2. Bind your item with `TameworkCommand`.
3. Decide how recipients are selected:
   - ordinary `ItemMetadata` owned-companion selection (the default flute UX)
   - owner-scope for a command-family roster
   - master-target only
   - linked plus master-target
4. Author the command list and its steps.
5. Make sure the target roles support the movement, state, and hook behavior you want.
6. Configure role-scoped companion policy in `TwCompanionConfig`.
7. Tune shared relocation infrastructure in `TwGlobalConfig`.
8. Test both the radial menu and the linked panel.

## Item Wiring Pattern
Typical setup:
```json
"Interactions": {
  "Primary": {
    "Interactions": [
      { "Type": "TameworkCommand" }
    ]
  },
  "Secondary": {
    "Interactions": [
      { "Type": "TameworkCommand", "CommandId": "OpenSelectionMenu" }
    ]
  }
}
```

Practical rule:
- keep primary for quick command dispatch
- keep secondary for radial selection unless your item’s UX demands something else

## Recipient Strategy
Choose `RosterStorage` and `MembershipMode` together:
- ordinary `ItemMetadata` flutes discover all owned companions and use
  per-item active records as the command selection; their panel selection is
  independent of shared player groups
- `LinkedOnly`: best for legacy curated companion rosters
- `OwnerScope`: best for “command all my nearby pets” tools or a durable command family
- `MasterTarget`: best for one-target tactical tools
- `LinkedOrMasterTarget`: best when you want a persistent roster plus a one-off targeted override

Then tighten the target set with:
- `RequireTamed`
- `RequireOwner`
- `AllowedRoles`
- `Radius`
- `RequireLineOfSight`

If `RequireOwner` is omitted, recipient ownership checks fall back to `TwGlobalConfig.OwnershipRequirements.LinkingRequiresOwner`.

## Authoring Commands
Keep each command focused on one gameplay intent:
- follow
- hold
- attack target
- move to ping
- set home
- return home
- trigger custom hook behavior

Use `ModeMapping` when a command should also represent the NPC’s current mode in the UI.

Use steps for the actual behavior:
- `SetState`
- `SetTarget`
- `ClearTarget`
- `ClearCombat`
- `MoveToPosition`
- `StoreHome`
- `TriggerHook`

If the same command needs special-case branching or deep custom logic, keep the command item simple and bridge into a hook.

## Linked Panel: What Comes From Config vs Runtime
Config-driven:
- which commands exist
- who can be targeted
- whether linking is enabled
- item cooldown and selection rules

Runtime-driven:
- owned row status such as selected, unselected, unloaded, captured, cooped, roster-stored,
  provisioned-dormant, dead, or Lost
- per-row actions such as recall, set home, return home, unlink, revive, release, or cull
- group membership, selection state, status tabs, nearby filter, and search/sort state
- current health, cooldown, breeding, and trait indicators

This boundary matters because not every companion-panel action is authored inside
`TwCommandItemConfig`. Ordinary `ItemMetadata` ownership discovery, per-item selection,
shared player groups, multi-membership edits, and status/search presentation are runtime
concerns. The held item's role and command options still decide whether a selected row
can receive a particular command.

## Role and Runtime Prerequisites
Confirm the target role or template includes the pieces needed by your commands:
- movement handling for move-to-position flows
- state names that match your `SetState` or `ModeMapping` values
- home storage or relocation support when you expose home-related commands
- hook consumers when you use `TriggerHook`

## Companion Policy Split
Put role-specific behavior in [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference):
- revive enablement
- revive cooldown

For dead companions, the linked panel reads the exact deadline saved in the
canonical death snapshot. Its countdown and Revive action therefore use the
same timing fact as restoration admission. Role-scoped `TwCompanionConfig`
owns the cooldown duration when one matches; the global value is only a
fallback.
- return-home and recall distance rules
- cross-world follow policy

Put shared infrastructure in [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference):
- relocation retry interval
- max relocation wait
- max relocation attempts
- unlink confirm in the linked panel

## Testing Checklist
1. For an ordinary `ItemMetadata` flute, confirm a newly owned compatible companion
   appears without a link operation.
2. Toggle one NPC's selection with the held flute and from its panel card; confirm
   another flute keeps its own selection.
3. Create a player group, assign one companion to several groups through the inline
   multi-select dropdown, and confirm clicking a group selects its members without
   removing other memberships.
4. Open the radial and verify default command selection.
5. Run each authored command while the NPC is loaded.
6. Test recall or return-home with the NPC unloaded.
7. Test dead or lost states if revive or recovery matters for the species.
8. Confirm role filters, unsupported-command messaging, and target caps behave as expected.

## Related Pages
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)



