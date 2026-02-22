# Config Discovery

This document explains how Tamework discovers asset based configuration and settings.

## Asset locations
- TwSpawnerConfig assets live under:
  `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- TwNameItemConfig assets live under:
  `<ModRoot>/Server/Tamework/Items/Naming/*.json`
- TwCommandItemConfig assets live under:
  `<ModRoot>/Server/Tamework/Items/Commands/*.json`
- TwInteractionConfig assets live under:
  `<ModRoot>/Server/Tamework/Interactions/*.json`
- TwGlobalConfig assets live under:
  `<ModRoot>/Server/Tamework/Global/*.json`

These asset types are registered with the asset registry and are available to any mod that ships assets at those paths.

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
TwGlobalConfig replaces the old settings file and is organized into top-level sections:
- `General` (`Enabled`, `Priority`)
- `OwnershipProtection` (`BlockOwnerDamage`, `BlockAllPlayerDamageIfOwned`, `InvulnerableIfOwned`)
- `InteractionDefaults` (`InteractionConfigParam`, `LovedItemsParam`, `IsHarvestableParam`, `IsMountableParam`, `HarvestContextParam`, `HarvestAlarmName`, `InteractionCooldownAlarmPrefix`)
- `Command`:
  - `ReturnHomeTeleportDistance`
  - `ReturnHomePathDistanceBeforeTeleport`
  - `ReturnHomeTeleportDelayMs`
  - `RecallSafeSpawnDistance`
  - `RecallForceRelocateDistance`
  - `RelocationRetryIntervalMs`
  - `RelocationMaxWaitMs`
  - `RelocationMaxRetryAttempts`
  - `DeadRespawnEnabled`
  - `DeadRespawnCooldownMs`
  - `DeadRespawnFollowRetryDelayMs`
  - `DeadRespawnDistanceClose`
  - `DeadRespawnDistanceNear`
  - `DeadRespawnDistanceMid`
  - `DeadRespawnDistanceFar`
  - `PlacementMinRelativeY`
  - `PlacementMaxRelativeY`
  - `LinkedPanelRequireUnlinkConfirm`

String parameter-name fields in `InteractionDefaults` are required; missing or blank values emit a warning on startup.
Command tuning fields are optional and fall back to built-in defaults when omitted or invalid.

## Reloading
- `/tw reloadconfig` reloads spawner + naming + command item configs from disk
  (`TwSpawnerConfig`, `TwNameItemConfig`, `TwCommandItemConfig`).
- TwInteractionConfig assets are managed by the asset registry and do not require a manual reload command.
