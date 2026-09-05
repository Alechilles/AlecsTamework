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

All toggles below accept `on` or `off`; omit the argument to toggle the current
state. They work from the server console and take effect immediately. Commands
change runtime state only. Restarting the server or loading/removing debug
config assets reapplies the active `TwDebugConfig` defaults.

| `DebugCommands` field | Runtime command |
| --- | --- |
| `Hook` | `/tw debug log hook [on\|off]` |
| `Spawner` | `/tw debug log spawner [on\|off]` |
| `Prompt` | `/tw debug log prompt [on\|off]` |
| `Ride` | `/tw debug log ride [on\|off]` |
| `Despawn` | `/tw debug log despawn [on\|off] [RoleName\|all\|clear]` |
| `DespawnRoleFilter` | `/tw debug log despawn [RoleName\|all\|clear]` |
| `Lag` | `/tw debug log lag [on\|off]` |
| `Coop` | `/tw debug log coop [on\|off]` |
| `Breeding` | `/tw debug log breeding [on\|off]` |
| `NeedsConsume` | `/tw debug log needs consume [on\|off]` |
| `NeedsDamage` | `/tw debug log needs damage [on\|off]` |
| `NeedsSeek` | `/tw debug log needs seek [on\|off]` |
| `NeedsTelemetry` | `/tw debug telemetry needs [on\|off]` |
| `Harvest` | `/tw debug log harvest [on\|off]` |
| `FlyingCompanion` | `/tw debug log companion flight [on\|off]` |
| `AvatarFlight` | `/tw debug log avatar-flight [on\|off]` |
| `RespawnTrace` | `/tw debug log respawn-trace [on\|off]` |

`Spawner` also seeds `/tw debug log spawner-location [on|off]`, which can be
changed independently at runtime. `DespawnRoleFilter` selects a role by name;
`all` or `clear` removes that filter.

`AvatarFlight` controls diagnostic logging. Controller tick logs also require
`Debug.LogControllerTicks` in the active avatar-flight config. `NeedsTelemetry`
still requires Tamework telemetry to be enabled.

## Defaults and Usage Notes
- The bundled default asset in `src/main/resources/Server/Tamework/Debug/TwDebugDefault.json` ships with local log-heavy debug toggles disabled and `NeedsTelemetry` enabled.
- `NeedsTelemetry` does not bypass the Tamework telemetry setting. If telemetry is disabled in `/tw settings`, no needs telemetry events are recorded.
- `NeedsTelemetry` does not enable the local needs-resource hot-path INFO summary. Enable `NeedsSeek` when you need that aggregate diagnostic.
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



