# Config Discovery

This document explains how Tamework discovers asset based configuration and settings.

## Asset locations
- TwSpawnerConfig assets live under:
  `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- TwInteractionConfig assets live under:
  `<ModRoot>/Server/Tamework/Interactions/*.json`

Both asset types are registered with the asset registry and are available to any mod that ships assets at those paths.

## Resolution and overrides
- Asset ids are derived from the filename (standard asset behavior).
- If multiple mods provide the same asset id, the later loaded asset wins.
- `Action_Tamework_Interact` resolves configs in this order:
  `ConfigId` override (if provided on the action), then the role param `InteractionConfigId` if present, then the enabled config with the highest `Priority` whose `RoleIds` contains the role id.
- `Priority` defaults to `0`. Higher values win. If multiple configs share the same priority, selection order follows asset map iteration.

## Settings file
`Tamework_Settings.json` is loaded from the plugin data directory:
`<UserData>/Mods/<ModName>/Server/Tamework/Tamework_Settings.json`

The file is seeded on first run if missing. It controls owner damage filtering:
- `BlockOwnerDamage`
- `BlockAllPlayerDamageIfOwned`
- `InvulnerableIfOwned`

## Reloading
- `/tw reloadconfig` reloads spawner item configs from disk (TwSpawnerConfig -> item feature registry).
- TwInteractionConfig assets are managed by the asset registry and do not require a manual reload command.
