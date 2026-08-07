# Spawn Beacon Visualization Design

## Goal

Add a player command that continuously reveals loaded, naturally created Hytale spawn beacons using the same model and nameplate presentation as command-created beacons. The visualization must not alter beacon spawning behavior.

## Command

`/tw showspawnbeacons [radius|off]`

- No argument enables tracking with a 64-block radius.
- A positive numeric argument enables tracking with that radius, clamped to 1-256 blocks.
- `off`, `false`, or `0` disables that player's tracking session.
- The command reports the number of loaded natural beacons currently covered and summarizes their spawn configuration IDs and positions.
- The command requires a live player and the existing `/tw` permission boundary.

## Base-Game Evidence

Hytale release 0.5.7 creates natural local beacons through
`LocalSpawnControllerSystem#tick` and `LegacySpawnBeaconEntity#create`. The
legacy beacon factory assigns `ModelComponent`, `PersistentModel`,
`DisplayNameComponent`, `PersistentDisplayName`, and `Nameplate`, using the
configured `BeaconNPCSpawn` model or the spawning plugin's marker-model
fallback. Manually created `SpawnBeacon` entities use the same model and
nameplate selection.

Because natural beacons already carry those components in the base-game
factory, mutating or re-adding them is not a reliable visualization strategy.
The debug feature will instead create a separate presentation-only proxy for
each covered natural beacon.

## Architecture

### Command and session registry

`TameworkShowSpawnBeaconsCommand` owns player tracking sessions keyed by player
UUID. A session records its world, requested radius, and generation ID. A
one-second delayed callback returns to the world thread, validates the player
reference and world, refreshes that player's coverage, then schedules the next
tick.

The registry computes the union of all active sessions in each world. Multiple
players therefore share one proxy for a covered source beacon. Disabling or
losing one session does not remove a proxy still covered by another active
session.

### Beacon discovery

Each refresh scans loaded entities for `LegacySpawnBeaconEntity`,
`TransformComponent`, and `UUIDComponent`. Only source beacons within at least
one active session's radius are retained. The scan does not load chunks or
create new natural beacons.

### Visual proxies

For every newly covered source beacon, the command creates one ordinary visual
entity at the source transform with:

- `UUIDComponent`
- `TransformComponent`
- `ModelComponent`
- `DisplayNameComponent`
- `Nameplate`
- `HiddenFromAdventurePlayers`

The model is resolved from the source beacon's current spawn wrapper using the
same selection as Hytale 0.5.7: the configured model when valid, otherwise the
spawning plugin's spawn-marker model. The nameplate is the source beacon's
spawn configuration ID.

The proxy must not contain `SpawnBeacon`, `LegacySpawnBeaconEntity`,
`LocalSpawnBeacon`, a spawn controller, persistent model/name components, or
any Tamework gameplay component. It cannot spawn NPCs, participate in beacon
spatial queries, or persist across a world save.

### Cleanup

On every refresh, a proxy is removed when its source beacon is invalid,
unloaded, or outside every active session radius. When the final session for a
world ends, every proxy owned by this feature in that world is removed.
Invalid/disconnected player references automatically end their sessions.

Proxy ownership is tracked in memory by source UUID and proxy reference. The
command removes only proxies it created and never removes or modifies natural
beacons.

## Safety and Failure Handling

- All entity scans and mutations run on the owning world thread.
- Discovery collects source snapshots before adding or removing proxies, so
  archetype changes do not occur during chunk iteration.
- If a source has no usable spawn wrapper, configuration ID, transform, or
  model fallback, it is skipped and counted in the command response rather
  than failing the command.
- A failed proxy creation logs one actionable warning with the source UUID and
  spawn configuration ID; recurring tracking ticks do not spam identical
  warnings.
- The existing unrelated debug-shape clear packet is not used, so this command
  cannot clear another debug feature's overlays.

## Documentation

Add the command to `docs/Actions-Sensors-Components.md` and clarify its
player-scoped behavior in `docs/Debugging.md`. Add a player-facing entry to the
current section of `CHANGELOG.md` without changing the release version.

## Verification

- Reuse the existing radius/off parser behavior rather than adding duplicate
  parser tests.
- Add a focused behavioral unit test only if proxy coverage ownership can be
  separated from engine ECS types and the test proves that one viewer ending
  does not remove a proxy still covered by another viewer.
- Run `bash ../gradlew :alecstamework:test`.
- Run the required player/thread-affinity grep.
- Validate changed engine references against the Hytale Workshop release/0.5.7
  index.
- Live verification, when available, should confirm that natural beacons use
  the same visible model/nameplate as manual beacons and that `off` removes
  only the presentation proxies.
