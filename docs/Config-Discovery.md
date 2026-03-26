# Config Discovery

This document explains where Tamework config assets live and how each family resolves.

## Asset locations
- `TwGlobalConfig`: `<ModRoot>/Server/Tamework/Global/*.json`
- `TwCompanionConfig`: `<ModRoot>/Server/Tamework/Companion/*.json`
- `TwInteractionConfig`: `<ModRoot>/Server/Tamework/Interactions/*.json`
- `TwSpawnerConfig`: `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- `TwNameItemConfig`: `<ModRoot>/Server/Tamework/Items/Naming/*.json`
- `TwCommandItemConfig`: `<ModRoot>/Server/Tamework/Items/Commands/*.json`
- `TwHappinessConfig`: `<ModRoot>/Server/Tamework/Happiness/*.json`
- `TwNeedsConfig`: `<ModRoot>/Server/Tamework/Needs/*.json`
- `TwBreedingConfig`: `<ModRoot>/Server/Tamework/Breeding/*.json`
- `TwTraitConfig`: `<ModRoot>/Server/Tamework/Traits/*.json`
- `TwCoopConfig`: `<ModRoot>/Server/Tamework/Items/Coops/*.json`

## Resolution patterns
### Single active global config
`TwGlobalConfig` resolves to highest enabled `Priority` (tie: case-insensitive lowest asset id).

### Role-scoped families
Resolved by role id + `Priority`:
- `TwCompanionConfig`
- `TwInteractionConfig`
- `TwHappinessConfig`
- `TwNeedsConfig`
- `TwBreedingConfig`
- `TwTraitConfig`

### Item-scoped families
Resolved by bound item ids:
- `TwSpawnerConfig` (`EmptyItemId`)
- `TwNameItemConfig` (`ItemId` / `ItemIds`)
- `TwCommandItemConfig` (`ItemIds`)

### Coop-scoped family
- `TwCoopConfig` by `CoopId`

## Priority and ties
- Higher `Priority` wins.
- For equal priority, most families use deterministic id-based tie-breaks.
- `TwInteractionConfig` selection remains priority-first with current asset-map iteration behavior for equal-priority ties.

## Parent fallback inheritance
All Tamework asset families above support parent fallback inheritance.

Behavior summary:
- Parent is resolved by parent key/id.
- Child keeps explicitly authored fields.
- Missing fields inherit from parent.
- Sectioned configs (for example `TwGlobalConfig`, `TwCompanionConfig`) inherit nested fields section-by-section.

## Global vs companion policy scope
- `TwGlobalConfig` contains global defaults and shared infrastructure knobs.
- `TwCompanionConfig` is the preferred role-scoped location for ownership protection and command behavior policy.
- Effective role settings fall back to global values when no role-scoped companion policy resolves.

## Asset-set gates
`TwGlobalConfig.AssetSets` gates optional bundled asset sets:
- `TranquilizerShortbow`
- `TranquilizerArrow`
- `TranquilizerPotion`

Gate evaluation is OR-based across enabled global configs:
- a gate is enabled if any enabled global config sets it true.

Recipe visibility reconciliation removes disabled gated tranquilizer recipes from crafting registries and restores them when enabled.

## Reloading
`/tw reloadconfig` reloads item-feature registries only:
- `TwSpawnerConfig`
- `TwNameItemConfig`
- `TwCommandItemConfig`

Other families are asset-registry driven and update through normal loaded/removed asset events.
