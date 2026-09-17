---
title: "Tamework Settings UI and Persistence"
order: 3
published: true
draft: false
---
# Tamework Settings UI and Persistence

`/tw settings` is the server-facing home for gameplay policy that should not be
duplicated across content packs.

It writes universe-local JSON under
`universe/Tamework/Settings/tamework-settings.json`. That settings file is
separate from the canonical companion database, `tamework-state.sqlite`;
settings do not form a second companion-lifecycle authority.

Edits remain pending until you click **Apply**. A bright amber warning below the
button row marks unsaved changes, with warning icons flashing on and off every
second. The warning clears after a successful apply, when all edits are reverted,
or when **Refresh** reloads the saved settings. Validation or save failures keep
the draft available so you can correct it and try again.

## Population limit

`LimitPerPlayerOwnedTotal` is a durable canonical owner cap:

- it counts saved profiles with that player as owner, including unloaded,
  captured, cooped, roster-stored, provisioned, dead, and Lost profiles;
- `0` disables the cap;
- `PerPlayerLimitScope` selects `PerWorld` or `Global`;
- the shared check is used by positive owner-acquisition paths including
  taming, owner assignment, breeding, NPC spawn, and filled-spawner release;
- positive acquisitions reserve capacity in their shared persistence operation;
  and
- sealed world evidence reconciles startup observations without treating
  temporary absence as ownership removal.

## SimpleClaims

The supported claims integration is direct SimpleClaims behavior:

- `SimpleClaimsEnabled` enables the bridge;
- breeding can require a claim;
- taming and breeding use configured per-claim-chunk and total-claim live
  limits; and
- `ProtectTamedFromNonMembers` enables SimpleClaims' native tamed-target
  damage policy.

There is no claim-provider dropdown or QuestLines Claims fallback.

## Other settings

The same UI continues to own the established taming, ownership, damage,
capture-owner, spawn-owner, needs-resource, and announcement settings. Apply
changes through the UI so validation and settings-file writes use one path.

## Animal progression

The **Animal Progression** section owns one shared owner-offline policy for
needs, aging, harvest readiness, and breeding readiness. The policy has two
modes:

- `OWNER_ONLINE_GRACE_THEN_DECAY` pauses all progression during the configured
  owner-offline grace period, then advances eligible timers at the configured
  multiplier.
- `ANY_LOADED_PLAYER` ignores owner presence. Needs still require a loaded
  animal; aging and readiness timers also advance while the animal is unloaded.

Tamework does not load, keep loaded, or simulate farm chunks in the background.
When an animal's chunk is unloaded, hunger and thirst do not change and no food
or water is consumed. Aging continues at its normal rate, without inventing a
care penalty that the unloaded farm could not process. Harvest and breeding
cooldowns can become ready, but do not accumulate extra harvests or litters;
pairing and birth still wait for loaded parents and world space.

Managed coop production catches up on loading using each resident's saved active
time. Each sweep processes at most 32 production intervals per resident and stops
when the container fills. Captured storage does not earn intervals. The shared
clock excludes server downtime and avoids counting simultaneous world ticks twice.

`Animal Aging` controls adult lifecycle behavior for all configured husbandry
animals. `Aging Disabled` keeps adult animals at full slaughter rewards.
`Freeze at Prime` is the default and stops an animal at its prime stage. `Full
Lifecycle` allows senior progression; enabling `Old-Age Death` then permits a
natural death after the configured senior duration. Natural death uses ordinary
non-slaughter drops and does not grant premium slaughter yield or husbandry XP.

Adult lifecycle durations use real minutes of eligible progression. They do not
scale with the server's day length. Captured or stored animals pause progression;
traded animals retain their accumulated age.

### Settings-file migration

Settings file version 2 stores this policy under `progression.animal`. On first
load, Tamework migrates version 1's `needs.tickPolicy` mode, grace hours, and
offline multiplier into that section without changing their values. The migration
adds the safe defaults `FREEZE_AT_PRIME` and old-age death disabled. Existing
needs-policy API names remain available for integrations and resolve the same
shared policy.

Revive enablement controls whether exact `DEAD_REVIVABLE` or `LOST` profiles
may restore. Role-scoped `TwCompanionConfig.Command.Revive` supplies the
gameplay cooldown, exact AND item-cost recipe, and optional insufficient-cost
message for roster-backed revival. Legacy item-linked restoration remains
free.

## Troubleshooting

- If the owner cap appears wrong, inspect canonical lifecycle ownership and
  reconciliation readiness; nearby loaded NPCs are not the complete count.
- If breeding is denied, verify the SimpleClaims claim and configured breeding
  limits.
- SimpleClaims damage integration errors fail open; they do not make a target
  invulnerable.
- Use `/tw debug persistence [status|health|detail|export]` for bounded
  replacement persistence diagnostics. None of these actions repairs or
  mutates saved persistence state; `export` writes only a redacted support ZIP.
