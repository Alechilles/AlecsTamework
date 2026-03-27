---
title: "Config Discovery, Resolution, and Inheritance"
order: 5
published: true
draft: false
---
# Config Discovery, Resolution, and Inheritance

Parent: [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index) | [Home](/mod/alecs-tamework/alecs-tamework-wiki)

This page is the working mental model for how Tamework finds config assets, chooses the active one, and applies parent fallback.

## Asset Families and Locations
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
- `TwDebugConfig`: `<ModRoot>/Server/Tamework/Debug/*.json`

## Pick the Right Family First
- Use `TwGlobalConfig` for shared server-wide defaults, infrastructure, asset-set gates, population limits, and SimpleClaims policy.
- Use `TwCompanionConfig` for role-scoped ownership and command behavior policy.
- Use `TwInteractionConfig` for optimized interaction authoring.
- Use `TwSpawnerConfig`, `TwNameItemConfig`, and `TwCommandItemConfig` when the behavior is bound to an item.
- Use `TwHappinessConfig`, `TwNeedsConfig`, `TwBreedingConfig`, and `TwTraitConfig` for progression state.
- Use `TwCoopConfig` when runtime behavior is keyed to a coop id.
- Use `TwDebugConfig` for dev-only default debug toggles.

## Resolution Patterns
### Single active config
These families resolve to one active asset:
- `TwGlobalConfig`
- `TwDebugConfig`

Rule:
- highest enabled `Priority` wins

### Role-scoped config
These families match by NPC role:
- `TwCompanionConfig`
- `TwInteractionConfig`
- `TwHappinessConfig`
- `TwNeedsConfig`
- `TwBreedingConfig`
- `TwTraitConfig`

Rule:
- highest enabled `Priority` whose `RoleIds` contains the role wins

### Item-scoped config
These families resolve from the item in use:
- `TwSpawnerConfig` by `EmptyItemId`
- `TwNameItemConfig` by `ItemId`
- `TwCommandItemConfig` by `ItemIds`

### Coop-scoped config
- `TwCoopConfig` resolves by `CoopId`

## Special Resolution Cases
### `TwInteractionConfig`
Optimized interactions have an additional runtime selection layer:
1. explicit `ConfigId` on `TameworkInteract`
2. role param named by `TwGlobalConfig.InteractionDefaults.InteractionConfigParam`
3. normal role-based priority resolution

Use `ConfigId` when deterministic selection matters.

### `TwGlobalConfig.SimpleClaims`
SimpleClaims settings are resolved from the best enabled global config that explicitly defines a `SimpleClaims` section. This prevents unrelated global configs from suppressing claim rules by accident.

## Priority and Tie Strategy
- Higher `Priority` wins.
- Keep priorities intentional rather than relying on tie behavior.
- Use a base config at low priority and higher-priority overlays for specific packs or environments.
- Avoid having multiple assets compete for the same exact role or item unless you are deliberately overriding one.

## Parent Fallback Contract
Tamework follows one consistent inheritance model:
- Omitted top-level key: inherit parent value.
- Explicit top-level object section: inherit missing nested keys from parent.
- Explicit scalar key: child value wins.
- Explicit array or map: child value replaces parent value.

Examples:
- Omit `Command` in a child `TwCompanionConfig` to inherit the entire section.
- Author only `Command.Travel.OnTransferFailure` in a child and all other `Travel` keys still inherit.
- Author `RoleIds`, `CommandList`, `Traits`, `Families`, or `IconOverrides` in a child and the parent array is replaced, not merged.

### Required Exception
- `TwBreedingConfig.RoleOverrides` is local-only and never inherited.

## Reload Boundaries
`/tw reloadconfig` only reloads item-feature families:
- `TwSpawnerConfig`
- `TwNameItemConfig`
- `TwCommandItemConfig`

Everything else refreshes through normal asset loaded and removed events:
- global
- companion
- interactions
- progression families
- coop configs
- debug config

## Practical Workflow
1. Decide whether the behavior is global, role-scoped, item-scoped, coop-scoped, or debug-only.
2. Put the asset in the correct folder first.
3. Keep priorities sparse and deliberate.
4. Use parent fallback for shared defaults, but remember arrays and maps replace.
5. Test the exact role, item, or coop id you expect to resolve.
6. Use `/tw reloadconfig` only when you edited an item config family.

## Related Pages
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference)
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [TwBreedingConfig Reference](/mod/alecs-tamework/twbreedingconfig-reference)
