---
title: "TwCoopConfig Reference"
order: 8
published: true
draft: false
---
# TwCoopConfig Reference

`TwCoopConfig` configures Tamework behavior for one `CoopId`.

## Location

`Server/Tamework/Items/Coops/*.json`

## Active runtime fields

- `Enabled`, `Priority`, `CoopId`, and optional `BlockTypeIds`
- `CapturePolicy.RequireTamed`, `ParticleSystem`, and `SoundEvent`
- `LifecycleRules.MaxResidents`, `ResidentRoamStartHour`,
  `ResidentRoamEndHour`, `ResidentSpawnOffset`,
  `CaptureWildNPCsInRange`, `WildCaptureRadius`, and `AcceptedRoleIds`
- `ProduceRules.DropsByRole`, `IntervalGameHours`, and `ItemsPerTick`

`CapturePolicy.OwnerRestricted`, `CapturePolicy.RequireOwner`, and
`IdentityRules` remain decoded compatibility fields. Automatic direct-live
coop capture has no acting player, and the canonical release protocol always
requires the current resident snapshot and treats entity UUIDs as replaceable
aliases. These fields do not select another persistence path.

## Runtime contract

- Capture accepts a live NPC, records the state needed for release, and removes
  that live source.
- Supported managed-coop interactions can move an eligible canonical captured
  item directly into an available slot and retire the exact source item.
- Release recreates the resident as a live NPC.
- Coops without an enabled matching config retain their ordinary behavior.

Live and captured-item intake use the same canonical coop-capture operation;
release uses the same resident authority.

## Inheritance

Parent fallback follows the standard Tamework config rules. Explicit child
values replace authored scalars, arrays, and maps; missing values inherit.
