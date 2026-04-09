---
title: "Tamework Settings UI and Persistence"
order: 14
published: true
draft: false
---
# Tamework Settings UI and Persistence

Parent: [Testing and Diagnostics](/mod/alecs-tamework/testing-and-diagnostics) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

This page explains what `/tw settings` changes, where those values are stored, and how they interact with config assets.

## Command
- `/tw settings` opens the curated in-game settings page.

Use it for live world tuning of high-impact server settings without editing many JSON files manually.

## Where Data Is Stored
- Universe settings file: `universe/Tamework/Settings/tamework-settings.json`
- Crash telemetry settings file: `universe/Tamework/Settings/crash-telemetry.json`

These files are universe-local runtime settings, not shipped mod assets.

## Settings Covered by the UI
### Population
- `LimitPerPlayerOwnedTotal`
- `PerPlayerLimitScope`

### SimpleClaims
- `SimpleClaimsEnabled`
- `LimitPerClaimChunk`
- `LimitPerClaimTotal`
- `BreedingRequiresClaim`
- `ProtectTamedFromNonMembers`

### Ownership and Interaction Defaults
- `BlockOwnerDamage`
- `BlockAllPlayerDamageIfOwned`
- `InvulnerableIfOwned`
- `CaptureRequiresOwner`
- `SpawnRequiresOwner`
- `InteractionRequiresOwner`
- `LinkingRequiresOwner`
- `CaptureClearsOwner`
- `SpawnSetsOwner`

### Needs and Revive
- Needs tick policy and needs-damage values (including model/rates/lethal)
- Revive system enabled toggle

### Crash Telemetry
- `enabled`
- `breadcrumbs_enabled`

## Runtime Behavior
- Applying settings writes updated files and refreshes runtime state.
- Crash telemetry enablement and breadcrumbs are applied immediately when possible.
- `/tw settings` is intended for world-level operations and diagnostics, not per-mod content packs.

## Relationship to `Tw*Config` Assets
- `TwGlobalConfig`, `TwNeedsConfig`, and related assets remain the content-authoring path.
- `/tw settings` provides a curated runtime override layer for common server-owner controls.
- `/tw reloadconfig` does not replace or clear settings stored by `/tw settings`.

## Best Practices
- Use config assets for shipped defaults and mod distribution.
- Use `/tw settings` for server-specific tuning after deployment.
- Keep a backup of `universe/Tamework/Settings/` before major balancing experiments.

## Related Pages
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference)
- [Integrations, Telemetry, and Build Workflow](/mod/alecs-tamework/integrations-telemetry-and-build-workflow)
