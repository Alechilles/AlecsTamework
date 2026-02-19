# Config Discovery

This document explains how Tamework discovers asset based configuration and settings.

## Asset locations
- TwSpawnerConfig assets live under:
  `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- TwNameItemConfig assets live under:
  `<ModRoot>/Server/Tamework/Items/Naming/*.json`
- TwInteractionConfig assets live under:
  `<ModRoot>/Server/Tamework/Interactions/*.json`
- TwGlobalConfig assets live under:
  `<ModRoot>/Server/Tamework/Global/*.json`

Both asset types are registered with the asset registry and are available to any mod that ships assets at those paths.

## Resolution and overrides
- Asset ids are derived from the filename (standard asset behavior).
- If multiple mods provide the same asset id, the later loaded asset wins.
- `Action_Tamework_Interact` resolves configs in this order:
  `ConfigId` override (if provided on the action), then the role param named by `TwGlobalConfig.InteractionConfigParam`
  (default `InteractionConfigId`) if present, then the enabled config with the highest `Priority` whose `RoleIds`
  contains the role id.
- `Priority` defaults to `0`. Higher values win. If multiple configs share the same priority, selection order follows asset map iteration.
- TwGlobalConfig resolves to the highest priority enabled asset. If multiple configs share the same priority, the lowest asset id (case-insensitive) is selected.

## Global config asset
TwGlobalConfig replaces the old settings file and controls owner damage filtering plus interaction defaults:
- `BlockOwnerDamage`
- `BlockAllPlayerDamageIfOwned`
- `InvulnerableIfOwned`
- `InteractionConfigParam`
- `LovedItemsParam`
- `IsHarvestableParam`
- `IsMountableParam`
- `HarvestContextParam`
- `HarvestAlarmName`
- `InteractionCooldownAlarmPrefix`
- `CommandReturnHomeTeleportDistance`
- `CommandReturnHomePathDistanceBeforeTeleport`
- `CommandReturnHomeTeleportDelayMs`
- `CommandRecallSafeSpawnDistance`
- `CommandRecallForceRelocateDistance`
- `CommandRelocationRetryIntervalMs`
- `CommandRelocationMaxWaitMs`
- `CommandRelocationMaxRetryAttempts`

String parameter-name fields are required; missing or blank values emit a warning on startup.
Numeric command-tuning fields are optional and fall back to built-in defaults when omitted or invalid.

## Reloading
- `/tw reloadconfig` reloads spawner + naming item configs from disk (TwSpawnerConfig + TwNameItemConfig).
- TwInteractionConfig assets are managed by the asset registry and do not require a manual reload command.
