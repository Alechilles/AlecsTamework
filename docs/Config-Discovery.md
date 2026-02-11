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
  `ConfigId` override (if provided on the action) then first enabled config whose `RoleIds` contains the role id.
- If multiple configs match a role id, selection order depends on asset map iteration. Avoid overlapping `RoleIds` or use `ConfigId` overrides when you need deterministic behavior.

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
