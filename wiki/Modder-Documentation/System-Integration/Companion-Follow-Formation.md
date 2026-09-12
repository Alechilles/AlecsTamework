# Companion follow formation

The development follow prototype uses a native flock led by the owner player.
Ground companions seek separate positions around the player. Flying companions
use the same arrangement above the player, with staggered heights. Positions do
not rotate with the player's facing or travel direction. Each follower's resting
area allows half its spacing in horizontal player movement before shifting toward
the player, so turning and taking a small step leaves its target in place.

The shared Simple, Simple TP, Advanced, Large, and Flying follow components try
`Component_Tamework_Instruction_Follow_Formation` before their normal movement.
Frost Dragon's flying wrapper inherits this through the shared Flying component.
The existing owner target and teleport thresholds remain in use.

## Conditions and fallback

The NPC must be tamed, in the root `Follow` state, and have its configured
`MasterTargetSlot` pointing to its actual owner. Native player flocks require
Adventure mode. Other game modes retain ordinary following. Mounted NPCs do not
use formation positioning.

The formation component uses `TreeMode` so an unavailable slot allows the caller's
normal follow instructions to run. Ground followers also fall back when a short
terrain probe detects an obstruction. This allows the group to compress at gates
and spread out after passing through. It does not add a new pathfinder.

Slots remain assigned while each follower is active; removing one does not
renumber the others. Ground and flying members have separate slot sequences in
the same native flock. Spacing uses the largest active hitbox in each sequence.
Positions are limited to 65% of the caller's recovery range, so very large or
crowded groups can have less than their preferred spacing.

## Parameters

| Parameter | Default | Meaning |
| --- | --- | --- |
| `MasterTargetSlot` | `MasterTarget` | Target slot containing the owner |
| `FormationRange` | `25` | Owner range before ordinary recovery takes over |
| `FormationSpacing` | `4` | Minimum spacing, enlarged for companion hitboxes |
| `FormationRelativeSpeed` | `1` | Speed while approaching the assigned position |
| `FormationAltitude` | `5` | Flying height above the owner, before height staggering |

The internal `TameworkFollowFormation` sensor exposes a position-only target.
It takes `TargetSlot`, `Range`, `Spacing`, and `Altitude`. It does not replace
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
state. Runtime records contain IDs and are scoped to weakly held stores. Native
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
