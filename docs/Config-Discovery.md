# Config Discovery and Overrides

This document explains how Tamework discovers item configs and how per‑world overrides work.

## Mod‑level config (defaults)
Each mod can include a default config in:
```
<ModRoot>/Server/Tamework/Tamework_Items_Config.json
```
This is the canonical config shipped with the mod.

## Per‑world overrides (local saves)
Overrides are stored per‑world in:
```
<UserData>/Saves/<World>/mods/<ModName>/Tamework_Items_Config.json
```
Notes:
- The per‑world file is auto‑created **empty** to avoid overriding future defaults.
- The mod name is taken from the manifest (not the zip name).
- If empty, defaults from the mod are used.

## Settings config
Tamework settings are stored per‑world and are copied from the mod defaults if missing. The default path is:
```
<UserData>/Saves/<World>/mods/<ModName>/tamework-settings.json
```

## Why this design
- Avoids breaking updates (defaults keep working even if new items are added).
- Allows players to override only what they need.

## Common pitfalls
- Putting overrides in `Server/Tamework` (that’s for the mod copy).
- Using the zip name instead of the manifest name for `<ModName>`.
