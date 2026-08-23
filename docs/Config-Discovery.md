# Config Discovery

This document explains where Tamework config assets live and how each family resolves.

## Asset locations
- `TwGlobalConfig`: `<ModRoot>/Server/Tamework/Global/*.json`
- `TwCompanionConfig`: `<ModRoot>/Server/Tamework/Companion/*.json`
- `TwCompanionMovementConfig`: `<ModRoot>/Server/Tamework/CompanionMovement/*.json`
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
- `TwDynamicAttachmentsConfig`: `<ModRoot>/Server/Tamework/DynamicAttachments/*.json`
- `TwAttachmentDisplayConfig`: `<ModRoot>/Server/Tamework/AttachmentDisplays/*.json`
- `TwTraitConfig`: `<ModRoot>/Server/Tamework/Traits/*.json`
- `TwCoopConfig`: `<ModRoot>/Server/Tamework/Items/Coops/*.json`
- `TwCapturePolicyConfig`: `<ModRoot>/Server/Tamework/CapturePolicies/*.json`
- `TwPopulationGroupConfig`: `<ModRoot>/Server/Tamework/PopulationGroups/*.json`
- `TwManagedActivityConfig`: `<ModRoot>/Server/Tamework/ManagedActivities/*.json`
- `TwBondedCompanionRosterConfig`: `<ModRoot>/Server/Tamework/BondedCompanions/Rosters/*.json`

## Resolution patterns
### Single active global config
`TwGlobalConfig` resolves to highest enabled `Priority` (tie: case-insensitive lowest asset id).

### Role-scoped families
Resolved by role id + `Priority`:
- `TwCompanionConfig`
- `TwCompanionMovementConfig`
- `TwInteractionConfig`
- `TwFoodConfig`
- `TwHappinessConfig`
- `TwNeedsConfig`
- `TwMountedGlideConfig`
- `TwBreedingConfig`
- `TwAttachmentMigrationConfig`
- `TwDynamicAttachmentsConfig`
- `TwTraitConfig`
- `TwCapturePolicyConfig`
- `TwPopulationGroupConfig` uses role matching to classify companions into one
  or more namespaced groups, then applies its configured global/per-world
  owned and active limits.
- `TwManagedActivityConfig` compiles provider-neutral activity profiles. A
  profile resolves an exact role through its population-group-backed family,
  exposes named capacity domains and activity mappings, and reports explicit
  readiness by profile id. Duplicate roles inside one profile are rejected.

### Dynamic attachment family
- `TwDynamicAttachmentsConfig` is indexed by role id and evaluates ordered conditional rules.
- Matching rules can either permanently update stored attachment selections or temporarily apply reversible overlays while the rule keeps matching.
- Runtime evaluation is intentionally low-frequency and fingerprinted so crowded worlds avoid per-tick attachment work.

### Companion movement family
- `TwCompanionMovementConfig` selects one enabled config per normalized role id: higher `Priority`, then case-insensitive lowest asset id.
- Its multiplier starts with `BaseMoveSpeedMultiplier`, multiplies matching attachment modifiers and the shared progression `MoveSpeedMultiplier`, then clamps to the configured bounds.
- Unmounted companions use the clamped value quantized to 5% steps through a static entity effect. `MountMovementConfig` supplies native mount controls; natively ridden companions instead scale the rider's runtime movement settings with the exact clamped value.

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
- `TwManagedActivityConfig` treats `Domains`, `Families`, and capability or
  mapping arrays as replacement sections when authored. An explicit
  `Activities` object inherits only its omitted nested fields. This prevents a
  child from silently combining two family or domain lists.

## Global vs companion policy scope
- `TwGlobalConfig` contains global defaults and shared infrastructure knobs.
- `/tw settings` owns high-impact server runtime policy such as the durable owner
  cap, ownership requirements, ownership damage protection, SimpleClaims
  breeding/damage options, needs resource mode, needs tick/damage policy,
  passive breeding enablement, and spawner owner transfer defaults.
- `TwCompanionConfig` is the role-scoped location for command distances, travel
  policy, placement rings, paid `Command.Revive` cost/cooldown policy,
  `Command.Summon` duration/storage/cooldown policy, and other companion
  command behavior.
- `TwBondedCompanionRosterConfig` defines bonded roster-family limits, session
  duration, summon cooldown, revive cooldown, revive price, and action gates.
  `ReviveCooldownSeconds: 0` disables the bonded revive cooldown.
- Legacy config fields for settings-owned values are still decoded for older packs, but new examples and `/tw config` hide them so server owners use `/tw settings`.

Persistence machinery does not have feature-specific asset families.
Population groups are authored through `TwPopulationGroupConfig`; paid revival
and timed summon balance are nested role policy in `TwCompanionConfig`.
Command rosters and companion provisioning are public integration authorities,
not asset families. The `feature_circuit` control plane is internal and
registry-derived, not a configurable rehearsal runtime or a return to the old
per-feature failure catalogs.

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

`TwCompanionMovementConfig` is asset-registry driven. Its loaded/removed events invalidate active companion speed fingerprints so live walking and native-mount speed values converge to the revised config.

This includes `TwCapturePolicyConfig`. A failed rebuild retains the last valid
compiled index and does not publish the invalid revision.

Managed-activity profiles use the same loaded/removed asset events. Their
registry compiles a complete candidate, increments its configuration revision
only after a successful replacement, and retains the last valid snapshot when
validation fails. No reload command or polling loop owns this family.

Managed profile readiness is fail-closed for missing, disabled, incomplete, or
invalid profiles. It includes profile and provider identity, provider contract
version, configuration revision, and a stable diagnostic detail. Required API
capabilities are validated as enum names; provider registration and runtime
capability availability are separate integration checks.

## Player-facing text
Player-facing string fields such as talent names/descriptions/branches, trait display names, command labels/messages, interaction messages, and happiness labels may be raw text or `server.lang` keys. Prefer language keys for built-in packs and public integrations so translations can be provided under `Server/Languages/*/server.lang` without editing behavior assets.
