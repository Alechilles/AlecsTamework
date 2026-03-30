---
title: "TwBreedingConfig Reference"
order: 22
published: true
draft: false
---
# TwBreedingConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference-index) | [Modder Documentation](/mod/alecs-tamework/modder-documentation-index)

## What It Controls
`TwBreedingConfig` defines breeding readiness, partner eligibility, pairing rules, cooldowns, passive breeding scans, inheritance behavior, offspring lifecycle defaults, and role-specific breeding overrides.

Use it when you want to control:
- which companions are allowed to breed
- how often they can breed
- whether breeding requires taming, adulthood, owner matching, or specific behavior states
- what the offspring inherits
- how baby and adolescent roles grow into adult roles

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
- `RequireSameRoleId`: requires the candidate pair to share the same role id.
- `RoleMaxNearbySameType`: per-role override list for the crowding cap.

Each `RoleMaxNearbySameType` entry supports:
- `RoleId`
- `MaxNearbySameType`

### `Cooldowns`
- `BaseCooldownSeconds`: base breeding cooldown in seconds.
- `BaseCooldownMinutes`: human-friendly alias for the same base cooldown. This is the preferred authored key when you want minute-scale tuning.
- `MinDelaySeconds`: minimum randomized pairing delay.
- `MaxDelaySeconds`: maximum randomized pairing delay.

If both cooldown keys are present, the minutes key writes the same backing value and should be treated as the preferred authored form.

### `PassiveBreeding`
- `Enabled`: allows non-interaction breeding sweeps.
- `SweepIntervalSeconds`: interval between passive breeding scans.
- `Basis`: timer basis for the passive scan cadence.

### `Timing`
- `Basis`: timer basis used for breeding cooldown and lifecycle timing.

Accepted values for both timing sections:
- `REAL_TIME`
- `WORLD_TIME_SCALED`

### `Inheritance`
- `InheritOwner`
- `InheritTamed`
- `InheritAttachments`
- `InheritTraits`

Nested `AttachmentInheritance`:
- `ParentWeight`: weight for inheriting from parent attachment selections.
- `RandomWeight`: weight for rolling from the available random pool.
- `MutationChance`: chance to mutate away from parent inheritance.

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
- `BabyRoleId`
- `AdolescentRoleId`
- `TimeToFullGrownSeconds`
- `TimeToFullGrownMinutes`
- `BabyStartScale`
- `AdolescentStartScale`
- `AdultStartScale`
- `AdolescentSwitchScale`
- `AdultSwitchScale`

Use a family entry when a specific adult role should resolve to a dedicated baby or adolescent role instead of relying only on the global lifecycle defaults.

### `RoleOverrides`
`RoleOverrides` is a map keyed by exact role id. Each value can override only the breeding sections that matter for that role:
- `Happiness`
- `Eligibility`
- `Pairing`
- `Cooldowns`
- `PassiveBreeding`
- `Timing`
- `Inheritance`
- `OffspringLifecycle`

Important behavior:
- `RoleOverrides` is local-only.
- Parent assets never contribute `RoleOverrides`.
- This is intentional and part of Tamework’s inheritance contract.

## Defaults, Aliases, and Cross-System Notes
- The bundled default asset in `src/main/resources/Server/Tamework/Breeding/TwBreedingConfig_Default.json` is the shipped baseline.
- Breed interactions use this family together with `TwHappinessConfig` and any fertility-related trait effects.
- `BaseCooldownMinutes`, `DefaultTimeToFullGrownMinutes`, `TimeToFullGrownMinutes`, and family-level `TimeToFullGrownMinutes` are author-facing minute aliases for the same stored second-based values.
- `InheritTraits` only matters if a compatible [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference) is also present.

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
    "RequireSameRoleId": true,
    "MaxNearbySameType": 8
  },
  "Cooldowns": {
    "BaseCooldownMinutes": 24,
    "MinDelaySeconds": 0,
    "MaxDelaySeconds": 300
  },
  "PassiveBreeding": {
    "Enabled": true,
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
      "MutationChance": 0.05
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

## Gotchas
- `RoleOverrides` does not inherit. If you need an override in a child asset, author it again.
- Explicit `Families` or `RoleMaxNearbySameType` arrays replace the parent list.
- Keep `Timing.Basis` and `PassiveBreeding.Basis` intentional. They solve different timing problems.
- New content should prefer minute-based keys where they exist, but old second-based keys remain valid.

## Related Pages
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
- [TwHappinessConfig Reference](/mod/alecs-tamework/twhappinessconfig-reference)
- [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference)
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)


