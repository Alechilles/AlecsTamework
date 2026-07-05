# Config Discovery

This document explains where Tamework config assets live and how each family resolves.

## Asset locations
- `TwGlobalConfig`: `<ModRoot>/Server/Tamework/Global/*.json`
- `TwCompanionConfig`: `<ModRoot>/Server/Tamework/Companion/*.json`
- `TwInteractionConfig`: `<ModRoot>/Server/Tamework/Interactions/*.json`
- `TwSpawnerConfig`: `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- `TwNameItemConfig`: `<ModRoot>/Server/Tamework/Items/Naming/*.json`
- `TwNamesConfig`: `<ModRoot>/Server/Tamework/Names/*.json`
- `TwCommandItemConfig`: `<ModRoot>/Server/Tamework/Items/Commands/*.json`
- `TwFoodConfig`: `<ModRoot>/Server/Tamework/Food/*.json`
- `TwHappinessConfig`: `<ModRoot>/Server/Tamework/Happiness/*.json`
- `TwNeedsConfig`: `<ModRoot>/Server/Tamework/Needs/*.json`
- `TwMountedGlideConfig`: `<ModRoot>/Server/Tamework/Mounts/Glide/*.json`
- `TwBreedingConfig`: `<ModRoot>/Server/Tamework/Breeding/*.json`
- `TwAttachmentMigrationConfig`: `<ModRoot>/Server/Tamework/AttachmentMigrations/*.json`
- `TwAttachmentDisplayConfig`: `<ModRoot>/Server/Tamework/AttachmentDisplays/*.json`
- `TwTraitConfig`: `<ModRoot>/Server/Tamework/Traits/*.json`
- `TwCoopConfig`: `<ModRoot>/Server/Tamework/Items/Coops/*.json`

## Resolution patterns
### Single active global config
`TwGlobalConfig` resolves to highest enabled `Priority` (tie: case-insensitive lowest asset id).

### Role-scoped families
Resolved by role id + `Priority`:
- `TwCompanionConfig`
- `TwInteractionConfig`
- `TwFoodConfig`
- `TwHappinessConfig`
- `TwNeedsConfig`
- `TwMountedGlideConfig`
- `TwBreedingConfig`
- `TwAttachmentMigrationConfig`
- `TwTraitConfig`

### Attachment display family
- `TwAttachmentDisplayConfig` resolves friendly attachment names from all enabled configs and entries.
- Exact role/model matches take precedence over namespace matches, which take precedence over global fallback entries.
- Higher `Priority` wins when multiple entries can label the same attachment.

### Item-scoped families
Resolved by bound item ids:
- `TwSpawnerConfig` (`EmptyItemId`)
- `TwNameItemConfig` (`ItemId`)
- `TwCommandItemConfig` (`ItemIds`)

### Name-pool family
- `TwNamesConfig` resolves by asset id (for example from `TwNameItemConfig.Naming.RandomNamesId`).

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
- `/tw settings` owns high-impact server runtime policy such as population caps, ownership requirements, ownership damage protection, revive enablement, claim-provider companion limits, needs resource mode, needs tick/damage policy, passive breeding enablement, and spawner owner transfer defaults.
- `TwCompanionConfig` is the role-scoped location for command distances, travel policy, cooldowns, placement rings, and other companion command behavior.
- Legacy config fields for settings-owned values are still decoded for older packs, but new examples and `/tw config` hide them so server owners use `/tw settings`.

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

## Player-facing text
Player-facing string fields such as talent names/descriptions/branches, trait display names, command labels/messages, interaction messages, and happiness labels may be raw text or `server.lang` keys. Prefer language keys for built-in packs and public integrations so translations can be provided under `Server/Languages/*/server.lang` without editing behavior assets.
