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
`IdentityRules` remain decoded compatibility fields and change nothing.
Automatic coop intake has no acting player, and a release always restores the
resident from its saved snapshot with a new entity UUID.

## Runtime contract

- Intake accepts an NPC that is out in the world, saves its state, and then
  removes the body. An owned companion's record moves to the coop slot. An
  unowned NPC (tamed or wild) taken in by `CaptureWildNPCsInRange` is stored in
  the slot itself and comes back exactly as it went in.
- Supported managed-coop interactions can move an eligible filled capture item
  directly into an available slot; the item is consumed once the companion is
  saved in the coop.
- Release recreates the resident in the world. Residents leave one at a time
  during the coop's roam hours.
- **Breaking the coop block releases every resident beside the block**, as a
  vanilla coop does. A companion that cannot be released stays recorded in the
  coop; an admin can restore it with `/tw companions restore`.
- Bodies in use are never taken in: ridden or riding NPCs, shoulder riders,
  avatar-flight bodies and timed summons are skipped.
- A companion in a coop counts toward its owner's owned limit, not the deployed
  limit.
- Coops without an enabled matching config retain their ordinary behavior.

### Production timing

Production mirrors the vanilla coop:

- It runs on the coop world's game time, never real time or owner-online time.
- Residents produce during the roam-hours sweep, before they are released, and
  at most once per roam window.
- Each resident's clock starts when it enters the coop, on every intake.
  Nothing carries over between stays.
- A resident whose role has an entry in `ProduceRules.DropsByRole` yields one
  unit per started interval of whole game hours since its clock: 0 hours gives
  0, 1 to 24 hours gives 1, 25 to 48 hours gives 2 at the 24 hour interval. The
  interval is `max(24, IntervalGameHours)` game hours. Catch-up is capped at 32
  units per sweep.
- Each unit makes `ItemsPerTick` drop rolls. Produce that does not fit in the
  coop's container is discarded.
- Owned and unowned residents follow the same rules.

## Inheritance

Parent fallback follows the standard Tamework config rules. Explicit child
values replace authored scalars, arrays, and maps; missing values inherit.
