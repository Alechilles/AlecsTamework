---
title: "TwBondedCompanionRosterConfig Reference"
order: 27
published: true
draft: false
---
# TwBondedCompanionRosterConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What it controls

`TwBondedCompanionRosterConfig` defines one policy family inside a bonded
companion roster. The policy controls eligible roles, owned and active limits,
optional session duration and summon cooldown, paid revive costs, and which
bonded actions are enabled.

This config applies only to Tamework's dedicated bonded lease model. It does
not alter permanent world-animal persistence, coops, ordinary command links,
generic owner/command-family rosters, generic timed summoning, or generic paid
revival.

## Asset location and resolution

- Location: `<ModRoot>/Server/Tamework/BondedCompanions/Rosters/*.json`
- Asset key: file name / asset ID
- Logical roster key: `RosterId`
- Policy-family key: `FamilyId`
- Runtime reload: `/tw reloadconfig`

Several assets may share one `RosterId` when they declare different
`FamilyId` values. This is how one command item can show independently balanced
families in one panel. For example, HyDragon's full dragons and Miniwyverns
share `hydragon:dragon_horn` while retaining separate capacities, timers,
feature gates, and revive recipes.

The `(RosterId, FamilyId)` pair must be unique. When a caller supplies only a
role, that role must resolve to exactly one family in the roster. Overlapping
role selectors are therefore ambiguous for role-only capture/provision and fail
closed.

## Inheritance and reload

- Parent fallback is supported.
- Omitted scalar fields inherit from the parent.
- `AllowedRoles` is an explicit array: an authored child array replaces the
  parent array.
- Omitting `RevivePrice` or `Features` inherits the complete parent object.
- When either nested object is authored, omitted nested fields inherit and
  explicit nested fields replace their parent value.
- An explicit `RevivePrice.Costs` array replaces the complete parent recipe.

Roster and dependent command-item configs are compiled as one coherent
generation. If any roster or command reference is invalid, the new generation
is rejected and the last accepted generation remains active.

## Structure

```json
{
  "Priority": 100,
  "RosterId": "example:shared_roster",
  "FamilyId": "example:large_companions",
  "AllowedRoles": [ "Tamed_Example_Large" ],
  "MaximumOwned": 0,
  "MaximumActive": 1,
  "SessionDurationSeconds": 600,
  "SummonCooldownSeconds": 300,
  "RevivePrice": {
    "Costs": [
      { "ItemId": "Example_Revive_Essence", "Quantity": 2 },
      { "ItemId": "Example_Domain_Essence", "Quantity": 4 }
    ]
  },
  "Features": {
    "Capture": true,
    "Provision": false,
    "Summon": true,
    "Dismiss": true,
    "Revive": true
  }
}
```

## Field reference

- `Priority`: integer retained with the compiled definition. Omission inherits;
  it does not make duplicate `(RosterId, FamilyId)` definitions valid.
- `RosterId`: required namespaced ID for the shared player-facing roster.
- `FamilyId`: required namespaced ID for this independently balanced family.
- `AllowedRoles`: required non-empty array of exact, unique role IDs.
- `MaximumOwned`: maximum stored, active, and dead profiles in this family for
  one owner. `0` means unlimited.
- `MaximumActive`: maximum active leases in this family for one owner. `0`
  means unlimited.
- `SessionDurationSeconds`: duration of one active lease. `0` disables expiry;
  positive values expire to `STORED`.
- `SummonCooldownSeconds`: cooldown applied when an active projection is
  stored. `0` disables the cooldown.
- `RevivePrice`: optional paid-revival definition. If present, `Costs` must be
  a non-empty ordered AND recipe.
- `Features`: action-specific policy toggles. Every toggle defaults to `true`
  when there is no inherited value.

Counts and timers cannot be negative. Zero is the only disabled/unlimited
sentinel. Runtime timestamps may be negative because Hytale world-time epochs
can be negative; consumers compare timestamps by ordering and reserve zero for
unset/unlimited.

## Revive recipe

Every `RevivePrice.Costs` entry requires:

- `ItemId`: non-blank item ID;
- `Quantity`: positive integer.

Every line is required. The bonded panel quotes the complete recipe, checks all
current quantities, and reserves the recipe as one atomic payment operation.
Do not split a multi-item recipe into independent external charges.

If `RevivePrice` is absent, no price is available. If `Features.Revive` is
`false`, the revive action is disabled even when a recipe exists.

## Feature toggles

- `Capture`: permits `StoreBondedCompanion` capture into this family.
- `Provision`: permits direct `BondedCompanionApi.provision` creation.
- `Summon`: permits `STORED -> ACTIVE`.
- `Dismiss`: permits explicit `ACTIVE -> STORED` through the panel/API.
- `Revive`: permits paid `DEAD -> STORED`.

Lifecycle recovery may still store an active projection after a non-death
exit even when explicit Dismiss is disabled. The toggle controls the player
action, not the runtime's obligation to converge safely.

## Shared-roster example

Two assets can route distinct families into one item:

```json
{
  "RosterId": "example:shared_roster",
  "FamilyId": "example:large_companions",
  "AllowedRoles": [ "Tamed_Example_Large" ],
  "MaximumOwned": 0,
  "MaximumActive": 1,
  "SessionDurationSeconds": 600,
  "SummonCooldownSeconds": 300
}
```

```json
{
  "RosterId": "example:shared_roster",
  "FamilyId": "example:small_companions",
  "AllowedRoles": [ "Tamed_Example_Small" ],
  "MaximumOwned": 1,
  "MaximumActive": 1,
  "SessionDurationSeconds": 0,
  "SummonCooldownSeconds": 0,
  "Features": {
    "Capture": false,
    "Provision": true,
    "Summon": true,
    "Dismiss": true,
    "Revive": true
  }
}
```

The command item names only `example:shared_roster`. Each profile retains its
resolved family ID, and all policy/action checks use that family.

## Validation scope

The field contract above is validated by Tamework's registered codec,
inheritance tests, coherent-generation tests, and packaged asset validation.
It does not claim validation against an exact Hytale `0.5.6` schema profile;
that matching profile was not available in the local schema catalog at the
time this reference was written.

## Gotchas

- Do not place these assets under `PopulationGroups`; the registered path ends
  in `BondedCompanions/Rosters`.
- Do not reuse a family ID twice inside one roster.
- Avoid overlapping allowed roles when role-only capture or provisioning must
  choose a family.
- `MaximumOwned: 0` and `MaximumActive: 0` mean unlimited, not disabled.
- `SessionDurationSeconds: 0` means the lease never expires.
- Revival returns a profile to `STORED`; it never summons automatically.
- Removing or invalidating a currently referenced family causes dependent
  positive actions to fail closed. It does not authorize generic fallback.

## Related pages

- [Bonded Companion API Reference](/mod/alecs-tamework/bonded-companion-api-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)
