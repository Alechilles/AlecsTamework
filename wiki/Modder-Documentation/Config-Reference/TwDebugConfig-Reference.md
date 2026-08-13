---
title: "TwDebugConfig Reference"
order: 25
published: true
draft: false
---
# TwDebugConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwDebugConfig` defines the default debug-toggle state for Tamework’s built-in debug commands. It is the config family you use when you want a dev or test environment to boot with specific debug channels already enabled.

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Debug/*.json`
- Scope: server-wide, single active config
- Resolution: highest enabled `Priority` wins

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level object sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- `TwDebugConfig` is not part of `/tw reloadconfig`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "Enabled": true,
  "Priority": 0,
  "DebugCommands": {
    "...": "..."
  }
}
```

## Section Reference
### `Enabled`
- Enables or disables this debug config asset.

### `Priority`
- Used to select the active debug config.

### `DebugCommands`
- `Hook`: default state for `/tw debughook`
- `Spawner`: default state for `/tw debugspawner`
- `Spawner` also seeds `/tw debugspawnerlocation` unless changed at runtime
- `Prompt`: default state for `/tw debugprompt`
- `Despawn`: default state for `/tw debugdespawn`
- `DespawnRoleFilter`: default role filter for despawn diagnostics
- `Lag`: default state for `/tw debuglag`
- `Coop`: enables coop-related debug output
- `Breeding`: enables breeding-related debug output
- `NeedsConsume`: enables needs-consumption debug output
- `NeedsDamage`: enables needs-damage diagnostics (`/tw debugneedsdamage`)
- `NeedsSeek`: enables needs-seek target resolution diagnostics (`/tw debugneedsseek`)
- `NeedsTelemetry`: enables rate-limited Alec's Telemetry needs context events (`/tw debugneedstelemetry`); events are still recorded only when Tamework telemetry is enabled
- `AvatarFlight`: enables transformed-player avatar-flight diagnostics; disabled by default and additionally requires the avatar-flight asset's `Debug.LogControllerTicks` setting
- `RespawnTrace`: enables capture, captured-item release, revival, and
  lost-recovery health-state diagnostics (`/tw debugrespawntrace`)

## Defaults and Usage Notes
- The bundled default asset in `src/main/resources/Server/Tamework/Debug/TwDebugDefault.json` ships with local log-heavy debug toggles disabled and `NeedsTelemetry` enabled.
- `NeedsTelemetry` does not bypass the Tamework telemetry setting. If telemetry is disabled in `/tw settings`, no needs telemetry events are recorded.
- This family controls default startup state. It does not replace the runtime commands themselves.
- `DespawnRoleFilter` is only meaningful when `Despawn` debugging is enabled.

## Minimal Example
```json
{
  "Enabled": true,
  "Priority": 100,
  "DebugCommands": {
    "Hook": true
  }
}
```

## Common Pattern Example
```json
{
  "Enabled": true,
  "Priority": 100,
  "DebugCommands": {
    "Hook": true,
    "Spawner": true,
    "Prompt": true,
    "Despawn": true,
    "DespawnRoleFilter": "Tamed_Rat",
    "Lag": false,
    "Coop": false,
    "Breeding": true,
    "NeedsConsume": true,
    "NeedsSeek": true,
    "NeedsTelemetry": true
  }
}
```

## Gotchas
- This config is for debug defaults, not player-facing settings.
- Keep test-only debug configs out of release builds unless you intentionally want persistent verbose logging.

## Related Pages
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)



