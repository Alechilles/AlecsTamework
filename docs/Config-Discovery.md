# Spawner Config Assets

This document explains how Tamework discovers spawner configs in the new asset-based system.

## Asset location
Spawner configs are **SpawnerConfig** assets stored under:
~~~
<ModRoot>/Server/Tamework/Items/Spawners/*.json
~~~

The asset Id should match the **empty spawner item ID**. Assets are loaded through the asset registry
like any other server asset.

## Overrides
Spawner configs are standard assets. If you need to override another mod's spawner settings, use:
- A patch/override mod
- A Hytalor patch that targets the spawner asset

There are no per-world override files for spawner configs.

## Settings config
Tamework settings are still stored per-world and are copied from the mod defaults if missing. The default path is:
~~~
<UserData>/Saves/<World>/mods/<ModName>/tamework-settings.json
~~~

## Reloading
After editing assets on disk, run:
~~~
/tw reloadconfig
~~~
This reloads spawner config assets from disk.
