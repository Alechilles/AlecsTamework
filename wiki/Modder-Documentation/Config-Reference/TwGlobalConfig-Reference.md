---
title: "TwGlobalConfig Reference"
order: 14
published: true
draft: false
---
# TwGlobalConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwGlobalConfig` is the shared server-wide config family for Tamework. Use it for defaults and infrastructure that should not be duplicated into every role or item config.

Use `TwGlobalConfig` for:
- interaction parameter names and cooldown alarm naming
- shared command relocation infrastructure and linked-panel safety rules
- bundled asset-set gates
- SimpleClaims permission-key integration details
- legacy compatibility fields that older packs may still contain

Do not use it for role-specific companion policy. That belongs in [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference).

Population caps, ownership requirements/protection, revive enablement, SimpleClaims enablement/limits, and related high-impact server policy are owned by `/tw settings`. Legacy config keys are still decoded, but new examples and `/tw config` hide them.

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Global/*.json`
- Scope: server-wide, single active config
- Resolution: highest enabled `Priority` wins, with deterministic asset-id tie-breaking
- Typical usage: one main active config, with optional child configs for specialized overrides

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays or maps replace the parent value rather than merging.
- `TwGlobalConfig` is not part of `/tw reloadconfig`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "General": { "Enabled": true, "Priority": 0 },
  "InteractionDefaults": { "...": "..." },
  "Command": { "...": "..." },
  "AssetSets": { "...": "..." },
  "SimpleClaims": { "...": "..." }
}
```

## Section Reference
### `General`
- `Enabled`: disables the asset entirely when `false`.
- `Priority`: used to select the active global config.

### `OwnershipProtection`
Legacy compatibility only. Use `/tw settings` for these runtime values.

- `BlockOwnerDamage`: blocks damage from the owner to owned NPCs.
- `BlockAllPlayerDamageIfOwned`: blocks all player damage once the NPC is owned.
- `InvulnerableIfOwned`: makes owned NPCs fully invulnerable.

### `OwnershipRequirements`
Legacy compatibility only. Use `/tw settings` for these runtime values.

- `CaptureRequiresOwner`: default capture owner requirement for spawner capture.
- `SpawnRequiresOwner`: default owner requirement for spawner spawn.
- `InteractionRequiresOwner`: default owner requirement for interaction entries that do not explicitly set `RequireOwner`.
- `LinkingRequiresOwner`: default owner requirement for command linking and command recipient checks when command-item config leaves `RequireOwner` unset.

### `InteractionDefaults`
These names are part of the optimized interaction contract. If you rename them here, your role params and action overrides must match.

- `InteractionConfigParam`: role param used to point an NPC at a specific `TwInteractionConfig`.
- `LovedItemsParam`: role param used by `UseLovedItems`.
- `IsHarvestableParam`: role param checked by harvest flows.
- `IsMountableParam`: role param checked by mount flows.
- `HarvestContextParam`: role param checked by harvest-context flows.
- `HarvestAlarmName`: alarm name used by harvest-ready checks.
- `InteractionCooldownAlarmPrefix`: prefix used to build per-entry cooldown alarm ids.

### `Command`
This section holds shared command infrastructure. Revive enablement is controlled by `/tw settings`.

- `ReturnHomeTeleportDistance`: distance at which return-home can stop pathing and teleport instead.
- `ReturnHomePathDistanceBeforeTeleport`: pathing threshold before teleport fallback is considered.
- `ReturnHomeTeleportDelayMs`: delay before teleport fallback executes.
- `RecallSafeSpawnDistance`: preferred placement distance when recalling an unloaded NPC.
- `RecallForceRelocateDistance`: distance after which recall can force relocation instead of waiting for normal follow.
- `RelocationRetryIntervalMs`: retry interval for queued off-screen relocations.
- `RelocationMaxWaitMs`: total relocation wait budget before the runtime gives up.
- `RelocationMaxRetryAttempts`: cap on relocation retry attempts.
- `DeadRespawnEnabled`: legacy compatibility field; `/tw settings` controls revive/respawn availability.
- `DeadRespawnCooldownMs`: respawn cooldown in milliseconds.
- `DeadRespawnCooldownMins`: human-friendly alias for the same cooldown. If both are present, the minutes key wins.
- `DeadRespawnFollowRetryDelayMs`: delay before follow retry after respawn.
- `DeadRespawnDistanceClose`: first candidate revive placement ring.
- `DeadRespawnDistanceNear`: second candidate revive placement ring.
- `DeadRespawnDistanceMid`: third candidate revive placement ring.
- `DeadRespawnDistanceFar`: final candidate revive placement ring.
- `PlacementMinRelativeY`: minimum vertical offset allowed during relocation placement.
- `PlacementMaxRelativeY`: maximum vertical offset allowed during relocation placement.
- `LinkedPanelRequireUnlinkConfirm`: requires explicit unlink confirmation in the linked panel.

### `AssetSets`
These toggles opt bundled Tamework assets into the live game. Any loaded active global config can enable them.

- `TranquilizerShortbow`
- `TranquilizerArrow`
- `TranquilizerPotion`
- `FeedTrough`
- `HerbivoreFeed`
- `CarnivoreFeed`

### `Population`
Legacy compatibility only. Use `/tw settings` for these runtime values.

- `LimitPerPlayerOwnedTotal`: cap on how many owned NPCs one player can have. `0` disables the cap.
- `PerPlayerLimitScope`: how the cap is counted.

Accepted values:
- `PerWorld`
- `Global`

### `SimpleClaims`
Most SimpleClaims runtime policy is owned by `/tw settings`. `AllowDamagePermissionKey` remains config-owned so integrations can keep a stable permission contract.

Top-level fields:
- `SimpleClaimsEnabled`: master gate for the integration.

Nested `Breeding`:
- `LimitPerClaimChunk`: max tamed breeding residents per claim chunk. `0` disables the limit.
- `LimitPerClaimTotal`: total max tamed breeding residents across the whole claim. `0` disables the limit.
- `BreedingRequiresClaim`: requires breeding to happen inside a valid claim.

Nested `Damage`:
- `ProtectTamedFromNonMembers`: blocks non-members from damaging tamed NPCs in claims.
- `AllowDamagePermissionKey`: SimpleClaims permission key that bypasses the damage restriction.

## Defaults, Aliases, and Compatibility Notes
- The bundled default asset in `src/main/resources/Server/Tamework/Global/TwGlobalConfig_Default.json` is the best reference for shipped baseline values.
- `DeadRespawnCooldownMins` is an alias for `DeadRespawnCooldownMs` and takes priority when both are authored.
- Settings-owned legacy sections remain readable for old packs, but `/tw settings` wins at runtime and `/tw config` hides those fields.

## Minimal Example
```json
{
  "General": {
    "Enabled": true,
    "Priority": 0
  },
  "InteractionDefaults": {
    "InteractionConfigParam": "InteractionConfigId"
  }
}
```

## Common Pattern Example
```json
{
  "General": {
    "Enabled": true,
    "Priority": 100
  },
  "InteractionDefaults": {
    "InteractionConfigParam": "InteractionConfigId",
    "LovedItemsParam": "AttractiveItemSet",
    "HarvestAlarmName": "Harvest_Ready",
    "InteractionCooldownAlarmPrefix": "TameworkInteract_Cooldown"
  },
  "Command": {
    "RelocationRetryIntervalMs": 2000,
    "RelocationMaxWaitMs": 20000,
    "RelocationMaxRetryAttempts": 60,
    "LinkedPanelRequireUnlinkConfirm": true
  },
  "AssetSets": {
    "FeedTrough": true,
    "HerbivoreFeed": true,
    "CarnivoreFeed": true
  },
  "SimpleClaims": {
    "Damage": {
      "AllowDamagePermissionKey": "tamework.damage_tamed_claim_npc"
    }
  }
}
```

## Gotchas
- Renaming `InteractionDefaults` keys without updating role params will silently break interaction resolution.
- Use `/tw settings` for ownership requirements and protection.
- `AssetSets` are global gates, not per-role toggles.
- Explicit maps and arrays replace parent values. Do not expect append behavior.
- Use `TwCompanionConfig` for role-scoped command distances and travel behavior. `TwGlobalConfig` is the shared infrastructure layer.

## Related Pages
- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)
- [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference)
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)
- [Command System and Linked Panel Guide](/mod/alecs-tamework/command-system-and-linked-panel-guide)



