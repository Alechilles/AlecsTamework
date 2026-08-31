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
- `/tw config open`, `/tw settings`
- `/tw config reload`
- `/tw debug get happiness`, `/tw debug get traits`, `/tw debug get lifestage`
- `/tw npc find <uuid>`
- `/tw npc clean <roleId>`
- `/tw debug view hitboxes`
- `/tw debug view spawnbeacons [radius|off]`
- `/tw debug persistence [status|health|detail|export]`
- `/tw debug persistence reviveready`

`/tw debug view spawnbeacons` tracks loaded natural spawn beacons around the caller
and reveals them to nearby Creative-mode players with the same configured model
and nameplate used by a manually created beacon. Its presentation-only proxies
do not participate in spawning and are removed when tracking ends.

## Debug toggles

- `/tw debug log hook`
- `/tw debug log prompt`
- `/tw debug log spawner`
- `/tw debug log spawnerlocation`
- `/tw debug log despawn`
- `/tw debug log lag`
- `/tw debug log coop`
- `/tw debug log needs consume`
- `/tw debug log needs damage`
- `/tw debug log needs seek`
- `/tw debug telemetry needs`
- `/tw debug log respawntrace`
- `/tw debug log xpevents`

`/tw debug log respawntrace` logs the stored and normalized health/needs projection,
the immediate live entity health and death-component state, first damage within
the trace window, and delayed 250 ms and 1 second probes. It covers Soul
Collector capture and release, free and paid companion restoration, and bonded
roster summons. Bonded summon traces also record the planned full-health
snapshot, profile, lease, world, projection result, and early placement, world,
thread, or exception failure. Enable it only for a short reproduction because
each return operation emits several correlated lines.

Dead-target capture denials always log the player, target, role, item, exact
health, and death-component state, even when the respawn trace is disabled.

Every newly inserted companion projection clears stale fall distance and
velocity and receives brief spawn-time fall protection. This gameplay guard is
active even when `/tw debug log respawntrace` is disabled. A cancelled invalid fall
can appear under `[tw-respawn-trace]` or `[tw-spawn-protection]`, depending on
active trace evidence.

Death and Lost restoration is a gameplay flow. Roster-backed companions can
use role-configured exact item costs, while legacy item-linked paths remain
free. `/tw debug persistence reviveready` is the exception for generic
`DEAD_REVIVABLE` profiles. It submits the normal persistence operation for
every dead generic linked profile owned by the calling player. The result shows
accepted, already-ready, and rejected counts. An accepted update completes
through the normal persistence workflow. The command asks the caller to retry
if linked roster data is still updating. It does not revive, summon, or change
bonded profiles.

For coops, test direct live capture, direct captured-item intake through the
supported managed-coop interaction, and resident release independently.

Command status comes from the canonical lifecycle projection. A relocation
timeout only drops the pending retry; it cannot manufacture `LOST`, and none of
the debug toggles changes that rule.

`/tw debug persistence status` and `health` print the same bounded
replacement-persistence summary. `detail` adds bounded feature, outbox,
operation-phase, incident, quarantine, and circuit counts. `export` writes a
bounded redacted support ZIP under Tamework's universe data directory without
including the database or save. None of these actions retries work or mutates
saved persistence state. Each response line is sent to the command caller and
written to the server log, including the export bundle path.
