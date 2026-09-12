# Companion follow formation

The development follow prototype uses a native flock led by the owner player.
Companions form compact rows around their own group center, initially
placed at the animals' average position. The group moves toward the player only
when it is too far away. The player is not a formation slot or the shape's center.
Turning or walking toward the group does not rotate it or make it move behind the
player. Flying companions use a separate group with staggered heights above the
player's altitude.

The shared Simple, Simple TP, Advanced, Large, and Flying follow components try
`Component_Tamework_Instruction_Follow_Formation` before their normal movement.
Frost Dragon's flying wrapper inherits this through the shared Flying component.
The existing owner target and teleport thresholds remain in use.

## Conditions and fallback

The NPC must be tamed, in `Follow` or non-combat `Defend.Default`, and have its configured
`MasterTargetSlot` pointing to its actual owner. Native player flocks require
Adventure mode. Other game modes retain ordinary following. Mounted NPCs do not
use formation positioning.

The formation component uses `TreeMode` so an unavailable slot allows the caller's
normal follow instructions to run. Ground followers also fall back when a short
terrain probe detects an obstruction. This allows the group to compress at gates
and spread out after passing through. It does not add a new pathfinder.

Ground and flying members use separate groups in the same native flock. Each
animal reserves half its larger horizontal hitbox dimension as a body radius.
Neighbors in a row use the sum of their radii plus a default 0.75-block empty gap.
For example, two animals with 2-block-wide hitboxes target centers 2.75 blocks
apart instead of the previous 6. Small neighbors no longer inherit the largest
animal's spacing. Rows use their largest radius for depth clearance, so a large
animal can still widen the space between its row and the next one.

The layout updates at the existing half-second cadence using linear row packing,
not an all-pairs collision solver. A swap and its new occupant radii are applied
in the same group refresh before sensors can publish the new targets. Arrival
tolerance is 0.25 blocks, leaving room within the default gap for small errors.
This is preferred target spacing; terrain and movement can still distort it.

The group moves closer to the player when its footprint needs more recovery room.
Hitbox spacing is not shrunk to force a large group into a small recovery range.
If the footprint itself cannot fit, ordinary follow recovery remains the fallback.

## Close follow and defending

Animal Husbandry's flutes offer a separate `FollowClose` command state. Role templates accept it in the same follow branch; the formation sensor declines it, so the existing seek/orbit and teleport behavior runs. Internal follow substates remain available. The direct interaction cycle and Recall still select `Follow`.

Defend's existing non-combat follow component uses formation slots in `Defend.Default`. `Defend.Combat` has no formation authority and keeps its normal combat movement. Ownership and owner-target checks apply to both formation modes.

## Flexible slot assignment

Companion follow groups, autonomous ground herds, and flying formations share
bounded slot exchanges. Only animals actively using that formation reserve slots.
They keep assignments unless exchanging two slots saves at least 20% of their
combined travel distance and at least half the comparison spacing (minimum 0.5 blocks). Companion groups use
the mean body diameter plus gap for this threshold; it does not set their layout
spacing. Native ground/flying formations keep their configured spacing.
Both animals then have a two-second swap cooldown. This reduces crossing after a
group becomes mixed up without demanding constant reshuffling. It is a local
improvement, not a search for a globally optimal assignment.

Each active group checks at most 128 pairs every 500 ms, resuming through larger
groups across checks. Ground comparisons use horizontal distance; flying groups
use three-dimensional distance. Ordinary updates copy position snapshots and look
up the current assignment. They add no pathfinding queries, world scans, or
background workers. Inactive slot reservations expire after two seconds, and surviving slots compact
to keep the group from remaining stretched out after departures. Native formation
caches are scoped to the world store and cleaned opportunistically in bounded
batches. A native flock shares unique slot numbers within each movement type;
only animals with matching formation geometry and spacing can exchange them.
The existing non-follow formation shapes and leader movement remain unchanged.

## Parameters

| Parameter | Default | Meaning |
| --- | --- | --- |
| `MasterTargetSlot` | `MasterTarget` | Target slot containing the owner |
| `FormationRange` | `25` | Owner range before ordinary recovery takes over |
| `FormationSpacing` | `4` | Fallback center spacing when a hitbox is unavailable |
| `FormationGap` | `0.75` | Desired empty gap between neighboring hitboxes |
| `FormationRelativeSpeed` | `1` | Speed while approaching the assigned position |
| `FormationAltitude` | `5` | Flying height above the owner, before height staggering |

The internal `TameworkFollowFormation` sensor exposes a position-only target.
It takes `TargetSlot`, `Range`, `Spacing`, `Gap`, and `Altitude`. It does not replace
`MasterTarget` with a synthetic entity. The ground motion uses native `Seek`;
the flying motion uses `TameworkFlyingOrbit` in `Approach` mode.

## Runtime lifecycle

Sensors report follow intent on the owning world thread. Joining and leaving
native flocks run through a queued world callback after sensor/ECS processing,
resolving entity IDs again before checking current ownership, state and target.
Tamework's admission checks replace wild-flock allowed-role/size checks for
these explicitly commanded companions.

The maintenance system visits only recorded active followers twice per second.
It checks current follow authority and expires intents after two seconds without
sensor evaluation. There is no global player/entity scan or new saved companion
state. Runtime records contain IDs and position snapshots, scoped to weakly held stores. Native
flock handling owns leader removal, dissolution and unload behavior. Leaving a
companion flock never changes ownership. Following transfers an animal out of
its previous native herd; there is no automatic restoration of that herd.

Native membership is saved by Hytale. If a following companion unloads before
cleanup, it can restore its former flock on reload even if the owner is absent.
This prototype does not yet reconcile that unloaded membership; verify this
boundary before relying on it in a persistent server.

## Live checks before release

This is a development prototype. Check mixed-size ground and flying groups in
Adventure mode, narrow gates, abrupt turns, stationary camera turns, Follow to
Hold/Idle, mounting, owner release, Creative-mode changes, logout, world changes,
and companion unload/reload. Java calculation tests and package checks do not
establish live navigation or lifecycle behavior.
