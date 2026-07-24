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
- Release recreates the resident as a live NPC.
- Captured items do not enter the coop; release the filled item through its
  normal spawner interaction first.
- Coops without an enabled matching config retain their ordinary behavior.

This is the only coop lifecycle path: direct live capture and direct live
release through the canonical persistence authority.

## Inheritance

Parent fallback follows the standard Tamework config rules. Explicit child
values replace authored scalars, arrays, and maps; missing values inherit.
