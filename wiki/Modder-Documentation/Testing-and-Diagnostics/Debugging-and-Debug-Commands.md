---
title: "Debugging and Debug Commands"
order: 13
published: true
draft: false
---
# Debugging and Debug Commands

Use this page when an asset or integration loads but behaves incorrectly.

## Recommended workflow

- Reproduce on a local server.
- Watch for asset decode, builder-registration, and config-resolution warnings.
- Verify the exact role, item, command item, or coop ID.
- Confirm the runtime jar and assets match the source being tested.

## Useful commands

- `/tw debug get owner`, `/tw debug set owner`
- `/tw debug get tamed`, `/tw debug set tamed`
- `/tw debug get alarm [AlarmName] [NpcUuid]`
- `/tw debug set harvestready [--mode=true|false|toggle] [NPC selectors]`
- `/tw config open`, `/tw settings`
- `/tw config reload`
- `/tw debug get happiness`, `/tw debug get traits`, `/tw debug get lifestage`
- `/tw debug set lifestage <baby|adolescent|adult|prime|senior> [NPC selectors]`
- `/tw npc find <uuid>`
- `/tw npc clean <roleId>`
- `/tw debug view hitboxes`
- `/tw debug view spawn-beacons [radius|off]`
- `/tw persistence start-fresh [confirm]`
- `/tw companions forget <self|player|UUID> <companion name or id> [confirm]`
- `/tw companions restore <self|player|UUID> <companion name or id> [confirm]`
- `/tw bonded grant <self|player|UUID> <rosterId> <roleId> [name]`

`/tw bonded grant` gives an online player one stored bonded companion for
testing and support, for example
`/tw bonded grant Alec hydragon:dragon_horn Tamed_RockDrakeT1 Boulder`. The
player name, roster ID and role ID match without regard to case. The
role must be an allowed role of exactly one family of that bonded roster, and
the family's `MaximumOwned` and the general owned companion limits still apply.
The grant does not need the family's `Provision` feature, which only controls
integrations. Each use creates a new companion. It has no body until its first
summon, which spawns it from the role. Without a name the companion shows its
role's name. The command works from the console and needs the `/tw` permission.

The life-stage setter updates juvenile growth timing, role, and scale together.
`baby` and `adolescent` require an enabled offspring lifecycle. `prime` requires
enabled adult aging, while `senior` also requires the Full aging mode. The
`juvenile` input is accepted as an alias for `adolescent`.

The harvest-readiness setter uses the alarm name from
`TwGlobalConfig.InteractionDefaults.HarvestAlarmName`. The default or `true` mode
clears that alarm so harvesting is ready immediately. `false` restarts the role's
normal `HarvestTimeout`, including configured progression modifiers. `toggle`
switches between those states.

`/tw debug view spawn-beacons` tracks loaded natural spawn beacons around the caller
and reveals them to nearby Creative-mode players with the same configured model
and nameplate used by a manually created beacon. Its presentation-only proxies
do not participate in spawning and are removed when tracking ends.

## Clearing test animals

Run `/tw debug clear-owned self` to preview ordinary animals owned by your player,
then `/tw debug clear-owned self confirm` to permanently clear them. Administrators
can replace `self` with an online player name or any player UUID. The command uses
the `tamework.command.tw` permission and also works from the console with a target.

Cleanup includes live, unloaded, dead, and lost ordinary animals. It releases
each companion in the companion store, which removes its ownership, command
links and saved state, then removes loaded animals. Unloaded animals are removed
when they next load. These animals are no longer owned or revivable. Keep the
companion menu closed until cleanup finishes.

Captured, cooped, and managed roster companions are skipped and reported. Bonded
companions are also preserved and are not included in the ordinary-profile counts.
Inventory items are untouched. Completion reports cleared, skipped, and failed
counts; busy or changed profiles are not forcibly deleted.

## Resolving a companion Tamework cannot reach

A companion in a capture item counts toward its owner's limit. Tamework cannot
see an item that was deleted by another mod, a cleared container or a rollback,
so such a companion would stay "captured" forever. The owner can fix this from
the companion panel with **Recall** or **Forget** on the captured card. Admins
have the same two actions for any player:

- `/tw companions forget <self|player|UUID> <companion name or id> [confirm]`
  releases a companion that is in a capture item for good and frees the owner's
  slot. It works from the console.
- `/tw companions restore <self|player|UUID> <companion name or id> [confirm]`
  restores a companion that is in a capture item, in a coop or lost, next to
  the admin who runs it. Run it in game, standing somewhere open.

Without `confirm` each command only says what it would do. The companion is
named by its display name or its profile ID; when several companions share a
name, the reply lists their IDs. After either command, any surviving copy of
the capture item, or the old coop entry, stops working: an item becomes an empty
capture item. Bonded companions are refused; use the bonded roster panel for
those. Both commands need the `tamework.command.tw` permission.

## Starting fresh when old data cannot be converted

When a world holds companion data from an older Tamework version that this
version cannot convert, Tamework creates no new companion store and turns
companion saving, capture, recall and the companion panel off. Every start logs
a console warning, and admins, operators and the local singleplayer owner get a
chat notice and a popup when they join. The normal fix is to run the Tamework
version named in the notice once on the world and then update again.

`/tw persistence start-fresh` is the alternative for a world whose old
companions do not need to be kept. Run it once to read what it does, then run
`/tw persistence start-fresh confirm`. It creates a new empty companion store
and asks for a server restart; companion features stay off until that restart.
Companions from the old data are not brought over. The old files are never
changed or deleted. Tamework stops looking at them once the new store exists.

The command uses the `tamework.command.tw` permission and works from the
console. On a world that is not waiting for a conversion it answers that there
is nothing to do. The new store's `meta.json` records the fresh start in a
`FreshStart` section with the kind of old data found and the time.

## Debug toggles

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

Additional runtime diagnostics include:

- `/tw debug log target-hud [on|off]`
- `/tw debug log xp-events [on|off]`
- `/tw debug avatar input [on|off|status]`
- `/tw debug avatar player-model unsafe [ModelId] [scale] | reset | status`

`/tw debug log respawn-trace` logs the stored and normalized health and needs values,
the immediate live entity health and death-component state, first damage within
the trace window, and delayed 250 ms and 1 second probes. It covers Soul
Collector capture and release, free and paid companion restoration, and bonded
roster summons. Bonded summon traces also record the planned full-health
snapshot, profile, generation, world, spawn result, and early placement, world,
thread, or exception failure. Enable it only for a short reproduction because
each return operation emits several correlated lines.

Dead-target capture denials always log the player, target, role, item, exact
health, and death-component state, even when the respawn trace is disabled.

Every companion body that Tamework spawns from saved state clears stale fall
distance and velocity and receives brief spawn-time fall protection. This gameplay guard is
active even when `/tw debug log respawn-trace` is disabled. A cancelled invalid fall
can appear under `[tw-respawn-trace]` or `[tw-spawn-protection]`, depending on
active trace evidence.

Revive (dead) and Recover (lost) are gameplay flows. Both restore the companion
from its saved snapshot. A revive can have a role-configured item cost; Recover
is free.

For coops, test direct live capture, direct captured-item intake through the
supported managed-coop interaction, and resident release independently.

Command status comes from the location on the companion's record in the
companion store (out in the world, in an item, in a coop, stored, dead or lost).
A recall of an unloaded companion that times out does not make it lost: Tamework
restores it near the player from its saved state. None of the debug toggles
changes that rule.

Tamework 5.0 has no `/tw debug persistence` command. The `status`, `health`,
`detail`, `export`, `reviveready`, `compact` and `simulateerror` subcommands
served the SQLite persistence that 5.0 replaced and were removed. To check the
store, read the server log and
[Companion Store and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths).
