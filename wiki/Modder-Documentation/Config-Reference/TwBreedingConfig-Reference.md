---
title: "TwBreedingConfig Reference"
order: 22
published: true
draft: false
---
# TwBreedingConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwBreedingConfig` defines breeding readiness, partner eligibility, pairing rules, cooldowns, passive breeding scans, inheritance behavior, offspring lifecycle defaults, and role-specific breeding overrides.

Use it when you want to control:
- which companions are allowed to breed
- how often they can breed
- whether breeding requires taming, adulthood, owner matching, or specific behavior states
- what the offspring inherits
- how baby and adolescent roles grow into adult roles
- whether related adult roles can breed as one lifecycle family

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Breeding/*.json`
- Scope: role-scoped
- Resolution: highest enabled `Priority` whose `RoleIds` contains the NPC role

## Inheritance and Reload
- Parent fallback is supported for the main asset body.
- Omitted top-level sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays and maps replace the parent value.
- Alias keys are treated as explicit overrides. This matters for cooldown and lifecycle minute-based keys.
- `RoleOverrides` is the required exception: it is local-only and never inherited from the parent.
- `TwBreedingConfig` is not reloaded by `/tw reloadconfig`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "Enabled": true,
  "Priority": 0,
  "RoleIds": [],
  "Happiness": { "...": "..." },
  "Eligibility": { "...": "..." },
  "Pairing": { "...": "..." },
  "Cooldowns": { "...": "..." },
  "PassiveBreeding": { "...": "..." },
  "Timing": { "...": "..." },
  "Gender": { "...": "..." },
  "Inheritance": { "...": "..." },
  "OffspringLifecycle": { "...": "..." },
  "RoleOverrides": {
    "Role_Id": { "...": "..." }
  }
}
```

## Section Reference
### `Enabled`, `Priority`, `RoleIds`
- `Enabled`: disables the config when `false`.
- `Priority`: used during role-match resolution.
- `RoleIds`: roles this asset applies to.

### `Happiness`
- `Threshold`: minimum effective happiness required before breeding can proceed.

The global "breeding requires happiness" switch lives in `/tw settings`. When it is off, this threshold is ignored at runtime.

### `Eligibility`
- `RequireTamed`
- `RequireAdult`
- `RequireNotInCombat`
- `RequireNotSleeping`

These gates apply before pairing starts.

### `Pairing`
- `BreedRadius`: search radius for finding a partner.
- `RequireWanderMode`: requires a breedable NPC to be in a roaming or wandering state before pairing.
- `RequireSameOwner`: only pair companions owned by the same player.
- `MaxNearbySameType`: crowding cap for nearby same-type NPCs.
- `RoleCompatibility`: controls candidate role matching. This is the preferred field for new content.
- `RequireSameRoleId`: deprecated legacy boolean compatibility field. It is still read for old configs. When `RoleCompatibility` is omitted, `true` resolves to `SameRole` and `false` resolves to `Any`.
- `RoleMaxNearbySameType`: per-role override list for the crowding cap.

Accepted `RoleCompatibility` values:
- `SameRole`: default behavior. Partners must have the same role id.
- `SameLifecycleFamily`: partners can share any adult role in the same `OffspringLifecycle.Families` entry, including same-role pairs.
- `DifferentFamilyRole`: partners must be different adult roles in the same `OffspringLifecycle.Families` entry.
- `Any`: broad compatibility. This matches legacy `RequireSameRoleId: false` behavior.

Each `RoleMaxNearbySameType` entry supports:
- `RoleId`
- `MaxNearbySameType`

`MaxNearbySameType` is a hard admission and execution limit, not only a partner-search hint. Manual and passive breeding share the same capacity service. Live nearby NPCs and children already admitted by pending litters both consume headroom, and the delayed spawn rechecks capacity before applying. A litter can therefore be partially admitted or rejected rather than exceeding the configured cap.

### `Cooldowns`
- `BaseCooldownSeconds`: base breeding cooldown in seconds.
- `BaseCooldownMinutes`: human-friendly alias for the same base cooldown. This is the preferred authored key when you want minute-scale tuning.
- `MinDelaySeconds`: minimum randomized pairing delay.
- `MaxDelaySeconds`: maximum randomized pairing delay.

If both cooldown keys are present, the minutes key writes the same backing value and should be treated as the preferred authored form.

### `PassiveBreeding`
- `SweepIntervalSeconds`: interval between passive breeding scans.
- `Basis`: timer basis for the passive scan cadence.

### `Timing`
- `Basis`: timer basis used for breeding cooldown and lifecycle timing.

Accepted values for both timing sections:
- `REAL_TIME`
- `WORLD_TIME_SCALED`

### `Gender`
Controls optional binary gender assignment and partner filtering. Omit this section, or set `Enabled` to `false`, to preserve existing ungendered behavior.

- `Enabled`: assigns stable `Male` or `Female` gender to companions covered by this breeding config.
- `RequireDifferentGender`: when enabled, breeding partners must have different assigned genders.
- `MaleWeight`: relative chance for a generated companion to be assigned `Male`.
- `FemaleWeight`: relative chance for a generated companion to be assigned `Female`.

Server owners can also turn the whole breeding-gender system off from `/tw settings`. That runtime toggle is enabled by default and acts as a global gate over the config values above.

When `OffspringLifecycle.Families[].AdultRoles[]` entries include `Gender`, offspring select a matching future adult role when possible. If no matching gendered choice is selectable, Tamework falls back to the normal weighted adult-role list so older mixed configs still work.

### `Inheritance`
- `InheritOwner`
- `InheritTamed`
- `InheritAttachments`
- `InheritTraits`

Nested `AttachmentInheritance`:
- `ParentWeight`: weight for inheriting from parent attachment selections.
- `RandomWeight`: weight for rolling from the available random pool.
- `MutationChance`: chance to mutate away from parent inheritance.
- `ExcludedSets`: exact model attachment-set IDs that inheritance must skip. Skipped sets keep the child's model-generated selection. When inherited from a parent config, an explicit array replaces the parent list and `[]` clears it.

### `OffspringLifecycle`
Controls growth defaults and optional family-specific role mappings.

- `Enabled`
- `DefaultTimeToFullGrownSeconds`
- `DefaultTimeToFullGrownMinutes`
- `TimeToFullGrownSeconds`
- `TimeToFullGrownMinutes`
- `DefaultBabyStartScale`
- `BabyStartScale`
- `DefaultAdolescentStartScale`
- `AdolescentStartScale`
- `DefaultAdultStartScale`
- `AdultStartScale`
- `DefaultAdolescentSwitchScale`
- `AdolescentSwitchScale`
- `DefaultAdultSwitchScale`
- `AdultSwitchScale`
- `Families`

Authoring guidance:
- Prefer the `Default...` keys for new content.
- The non-`Default` top-level keys are compatibility aliases that write the same fallback values.

Each `Families` entry supports:
- `AdultRoleId`
- `AdultRoles`
- `BabyRoleId`
- `AdolescentRoleId`
- `TimeToFullGrownSeconds`
- `TimeToFullGrownMinutes`
- `BabyStartScale`
- `AdolescentStartScale`
- `AdultStartScale`
- `AdolescentSwitchScale`
- `AdultSwitchScale`

Use a family entry when one adult role, or a set of related adult roles, should resolve to a dedicated baby or adolescent role instead of relying only on the global lifecycle defaults.

`AdultRoleId` is the legacy single-adult field and remains valid. New cross-role families should prefer `AdultRoles`, which supports weighted adult choices:
```json
{
  "AdultRoles": [
    { "RoleId": "Deer_Stag", "Gender": "Male", "Weight": 1.0 },
    { "RoleId": "Deer_Doe", "Gender": "Female", "Weight": 1.0 }
  ],
  "BabyRoleId": "Deer_Fawn"
}
```

When `AdultRoles` is present:
- Every listed adult role is treated as part of the same lifecycle family.
- `SameLifecycleFamily` pairing can match those adult roles to each other.
- `DifferentFamilyRole` pairing can require two different adult roles from the same family.
- The baby spawn role comes from `BabyRoleId` when it is valid.
- The future adult role is selected once at birth using the configured weights and is persisted for growth.

Invalid, blank, or non-positive weighted adult entries are ignored for selection.

### `RoleOverrides`
`RoleOverrides` is a map keyed by exact role id. Each value can override only the breeding sections that matter for that role:
- `Happiness`
- `Eligibility`
- `Pairing`
- `Cooldowns`
- `PassiveBreeding`
- `Timing`
- `Gender`
- `Inheritance`
- `OffspringLifecycle`

Important behavior:
- `RoleOverrides` is local-only.
- Parent assets never contribute `RoleOverrides`.
- This is intentional and part of Tamework’s inheritance contract.

## Legacy Settings-Owned Fields Accepted
These legacy fields are still decoded for old packs, but they are hidden from `/tw config` and controlled at runtime by `/tw settings`:
- `PassiveBreeding.Enabled`
- `RoleOverrides.*.PassiveBreeding.Enabled`

## Defaults, Aliases, and Cross-System Notes
- The bundled default asset in `src/main/resources/Server/Tamework/Breeding/TwBreedingConfig_Default.json` is the shipped baseline.
- Breed interactions use this family together with `TwHappinessConfig` and any fertility-related trait effects.
- `BaseCooldownMinutes`, `DefaultTimeToFullGrownMinutes`, `TimeToFullGrownMinutes`, and family-level `TimeToFullGrownMinutes` are author-facing minute aliases for the same stored second-based values.
- Passive breeding enablement, breeding happiness requirement, and the global breeding-gender gate are settings-owned runtime policy.
- `InheritTraits` only matters if a compatible [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference) is also present.

## Litter Semantics
- One fertility roll determines the litter before the delayed sequence begins.
- Expected offspring is the product of both resolved parent fertility multipliers, clamped to `0..4`.
- The whole-number portion is guaranteed, and one fractional roll can add at most one child. The intentional result is zero through four offspring.
- Similar-looking siblings from one litter are expected.
- Manual and passive breeding apply the same eligibility, nearby-count, and
  direct SimpleClaims breeding rules.

## Signed-Time Contract
Hytale world-time epoch values can be negative. `Timing` and `PassiveBreeding` scheduling preserve signed deadlines and compare by ordering; only `0` is the unset sentinel. Do not use positive-value checks when integrating with breeding cooldown or sweep state.

## Minimal Example
```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": [
    "My_Tamed_Wolf"
  ],
  "Eligibility": {
    "RequireTamed": true,
    "RequireAdult": true
  },
  "Cooldowns": {
    "BaseCooldownMinutes": 30
  }
}
```

## Common Pattern Example
```json
{
  "Enabled": true,
  "Priority": 100,
  "RoleIds": [
    "My_Tamed_Wolf"
  ],
  "Happiness": {
    "Threshold": 70.0
  },
  "Eligibility": {
    "RequireTamed": true,
    "RequireAdult": true,
    "RequireNotInCombat": true,
    "RequireNotSleeping": true
  },
  "Pairing": {
    "BreedRadius": 15.0,
    "RequireSameOwner": true,
    "RoleCompatibility": "SameRole",
    "MaxNearbySameType": 8
  },
  "Cooldowns": {
    "BaseCooldownMinutes": 24,
    "MinDelaySeconds": 0,
    "MaxDelaySeconds": 300
  },
  "PassiveBreeding": {
    "SweepIntervalSeconds": 30,
    "Basis": "REAL_TIME"
  },
  "Timing": {
    "Basis": "WORLD_TIME_SCALED"
  },
  "Inheritance": {
    "InheritOwner": true,
    "InheritTamed": true,
    "InheritAttachments": true,
    "InheritTraits": true,
    "AttachmentInheritance": {
      "ParentWeight": 1.0,
      "RandomWeight": 0.25,
      "MutationChance": 0.05,
      "ExcludedSets": [
        "Saddle",
        "SaddleBlanket"
      ]
    }
  },
  "OffspringLifecycle": {
    "Enabled": true,
    "DefaultTimeToFullGrownMinutes": 7,
    "DefaultBabyStartScale": 0.55,
    "DefaultAdolescentStartScale": 0.8,
    "Families": [
      {
        "AdultRoleId": "My_Tamed_Wolf",
        "BabyRoleId": "My_Tamed_Wolf_Baby",
        "TimeToFullGrownMinutes": 48
      }
    ]
  },
  "RoleOverrides": {
    "My_Tamed_Wolf": {
      "OffspringLifecycle": {
        "Families": [
          {
            "AdultRoleId": "My_Tamed_Wolf",
            "BabyRoleId": "My_Tamed_Wolf_Baby",
            "TimeToFullGrownMinutes": 48
          }
        ]
      }
    }
  }
}
```

## Cross-Role Family Example
This pattern allows two different adult roles to breed together, produce one shared baby role, and grow into one of the configured adult roles. Gender is optional; this example enables it so stag/doe pairs are required and offspring grow into an adult role matching the assigned gender. Use `SameLifecycleFamily` instead when same-role pairs in the family should also be valid.

```json
{
  "Enabled": true,
  "Priority": 100,
  "RoleIds": [
    "Deer_Stag",
    "Deer_Doe",
    "Deer_Fawn"
  ],
  "Pairing": {
    "RoleCompatibility": "DifferentFamilyRole",
    "RequireSameOwner": false,
    "MaxNearbySameType": 8
  },
  "Gender": {
    "Enabled": true,
    "RequireDifferentGender": true,
    "MaleWeight": 1.0,
    "FemaleWeight": 1.0
  },
  "OffspringLifecycle": {
    "Enabled": true,
    "DefaultTimeToFullGrownMinutes": 48,
    "Families": [
      {
        "AdultRoles": [
          { "RoleId": "Deer_Stag", "Gender": "Male", "Weight": 1.0 },
          { "RoleId": "Deer_Doe", "Gender": "Female", "Weight": 1.0 }
        ],
        "BabyRoleId": "Deer_Fawn"
      }
    ]
  }
}
```

## Gotchas
- `RoleOverrides` does not inherit. If you need an override in a child asset, author it again.
- Explicit `Families`, `RoleMaxNearbySameType`, or `AttachmentInheritance.ExcludedSets` arrays replace the parent list.
- Keep `Timing.Basis` and `PassiveBreeding.Basis` intentional. They solve different timing problems.
- New content should prefer minute-based keys where they exist, but old second-based keys remain valid.
- Gender labels appear in linked companion panels and preserved spawner tooltips for companions covered by an enabled gender config.
- A multi-child litter is not automatically a duplication bug. Check `/tw gethappiness` for the active job's planned, admitted, and outstanding counts before investigating repeated entities.

## Related Pages
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
- [TwHappinessConfig Reference](/mod/alecs-tamework/twhappinessconfig-reference)
- [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference)
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)



