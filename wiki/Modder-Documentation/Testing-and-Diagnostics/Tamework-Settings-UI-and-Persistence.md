---
title: "Tamework Settings UI and Persistence"
order: 3
published: true
draft: false
---
# Tamework Settings UI and Persistence

Parent: [Testing and Diagnostics](/mod/alecs-tamework/testing-and-diagnostics) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

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

## Experience presets

Selecting a preset fills the form immediately; **Apply** saves it. **Custom**
leaves the current form unchanged. There is no separate Load Preset step.

**Hardcore** enables full adult aging and old-age death for configured animals,
disables revives and recall teleportation, and sets starvation/dehydration damage
to 10%/15% of maximum health per minute. The other standard presets restore
revives and recall teleportation, disable old-age death, and restore the 2%/3%
damage rates. Only Hardcore changes the aging mode. Review the form before applying;
presets preserve ownership, claims, population limits, and other custom policies.

## Population limits

There are two per-player limits. Both use `perPlayerLimitScope` (`PerWorld` or
`Global`), and `0` means no limit. The default for both is `0`.

| Setting | Key in `tamework-settings.json` | Counts |
| --- | --- | --- |
| Max companions out in the world per player | `population.limitPerPlayerDeployedTotal` | Companions that are out in the world, loaded or not. |
| Max companions owned per player | `population.limitPerPlayerOwnedTotal` | Every owned companion: out, stored, in items, in coops, dead and lost. |

- The deployed limit is checked when a change puts a companion out in the world:
  a tame, a summon, a release from a capture item, a revive, a recover, a coop
  release, a birth, or a tamed spawn. Storing or capturing a companion frees a
  place.
- The owned limit is checked when a player gets one more companion: a tame, a
  capture, a provision or a claim. Summoning a companion the player already owns
  is not an owned-limit change.
- A change that adds nothing always passes. A player who is already over a
  lowered limit keeps every companion.
- Each limit has its own message for the player.
- Population groups (`TwPopulationGroupConfig`) keep their own owned and
  deployed limits and are checked as well.

### Upgrading from 4.x

In 4.x, `limitPerPlayerOwnedTotal` counted only the loaded companions out in the
world. In 5.0 that key counts every owned companion, so the old number would be
much stricter. To keep the old meaning, a settings file that has
`limitPerPlayerOwnedTotal` and no `limitPerPlayerDeployedTotal` is changed once
when it is read: the old value becomes the deployed limit and the owned limit
becomes `0` (no limit). The file is saved with both keys, and from then on the
two values are independent. The console logs one line when this happens.

If you edit the file by hand, keep both keys. A file with only the owned key is
read as a 4.x file.

A companion imported from a 3.x or 4.x world that has not been seen since the
import does not count toward the deployed limit until its animal is found. It
counts as owned.

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
capture owner requirement, and needs-resource settings. Apply
changes through the UI so validation and settings-file writes use one path.

**Captured companion ownership** replaces the former "Capture Clears Owner" and
"Spawn Sets Owner" toggles. It is one choice, saved as
`ownership.capture.captureItemOwnership`: `FOLLOWS_ITEM` (default, the owner is
whoever holds the capture item, if their limits allow), `OWNER_ONLY` (only the
owner can pick up or release the item) or `CHANGES_ON_RELEASE` (whoever
releases the companion becomes its owner). A settings file that still has only
the old `captureClearsOwner` and `SpawnSetsOwner` values is mapped on load:
cleared owner maps to `FOLLOWS_ITEM`, kept owner with assignment to
`CHANGES_ON_RELEASE`, and kept owner without assignment to `OWNER_ONLY`. See
the TwSpawnerConfig reference for the full rules.

The "Spawn Requires Owner" toggle is retired: the ownership mode alone decides
who may release a captured companion (`OWNER_ONLY` is the restriction). A saved
`ownership.capture.spawnRequiresOwner` value still loads without error and is
ignored for release.

## Welcome and announcements

In `/tw settings`, turn off **Welcome and announcements** and click **Apply**
to disable all automatic welcome and update popups. **Refresh** reloads the saved
value, and experience presets leave it unchanged.

You can also set
`"enabled": false` in
`universe/Tamework/Settings/tamework-settings-announcement.json`.
This applies to everyone, including first-time players and future update notices.
Tamework reads the file on each announcement attempt, so no restart is needed.
The setting does not close an already open popup. `/tw news` can still open the
announcement manually.

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
scale with the server's day length. Animals in capture items pause progression;
managed coop residents can continue under the shared progression policy. Traded
animals retain their accumulated age.

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
- Tamework 5.0 has no persistence status or export command. The older
  `/tw debug persistence` subcommands `status`, `health`, `detail` and `export`
  are no longer registered. Collect the server log when you report a problem.
  For a world updated from 3.x or 4.x, see
  [World Migration for Server Admins](/mod/alecs-tamework/world-migration-for-server-admins).
