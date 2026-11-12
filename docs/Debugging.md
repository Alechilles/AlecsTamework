# Debugging and Testing

## Recommended workflow
- Test changes on a local server first.
- Watch server logs for asset decode, builder, and runtime warnings.
- Validate config resolution for the exact role/item/coop id under test.

## Common log patterns
- `Builder ... does not exist` -> missing builder registration or load-order issue.
- `Unknown JSON attribute ...` -> field name mismatch for that builder/asset codec.
- `TameworkInteract: no config resolved or config disabled` -> config resolution mismatch (`ConfigId`, role param, or `RoleIds`).
- `TameworkInteract: no interactions matched` -> requirements failed; inspect requirement summary and alarm/context state.
- `TwGlobalConfig ... missing required fields` -> one or more required interaction default param names are blank.

## Server-console command scope

Server-wide diagnostics and controls do not require a player identity. This
includes `/patchwork status`, `/patchwork reload`, `/patchwork selftest`, and
the server-global `debug*` logging toggles. Patchwork administration requires
the `patchwork.admin` permission.
`/tw debug telemetry crash` status and `flush` are also console-safe; its simulated
event/crash actions remain restricted to the existing allowlisted player identities.
Tamework 5.0 removed the `/tw debug persistence` command and all its
subcommands (`status`, `health`, `detail`, `export`, `reviveready`, `compact`,
`simulateerror`) together with the SQLite persistence they served.
`/tw persistence start-fresh [confirm]` is console-safe. It only applies while
old companion data blocks the world; see the wiki page "World Migration for
Server Admins".

Commands that operate on a world but not a player use Hytale's optional world
argument. Console callers must provide the target world for `/tw config reload`,
`/tw npc clean`, `/tw npc find`, and `/tw debug get alarm`. The last
two require an NPC UUID when no player gaze target exists; player-relative distance
is reported as `n/a` from the console.

`/patchwork reload` rescans definitions and rewrites the generated patch pack.
Tamework does not install a host-specific live-reload adapter, so status can
truthfully report regenerated targets as restart-required until the server is
restarted.

Player UI, held-item, gaze-only, player-overlay, and live API fixture commands remain
player-scoped. In particular, `/tw config open`, `/tw settings`, `/tw news`,
`/tw api test prepare|reset|run|status`, `/tw debug view hitboxes`,
`/tw debug view spawn-beacons`, and `/tw debug view spawn-markers` need a live player.
`/tw npc spawn tamed` needs one unless it names `--owner` and `--at` (see "Console setup of owned and shared
animals"). `/tw share ...` acts for the calling player; `/tw debug share ...` is its admin form.

## Interaction troubleshooting
- Verify matching enabled `TwInteractionConfig` with expected `RoleIds` and `Priority`.
- If multiple configs apply, set explicit `ConfigId` on `TameworkInteract` for deterministic selection.
- Confirm role params referenced by `TwGlobalConfig.InteractionDefaults` exist and have expected values.
- Use `/tw debug get alarm` for harvest/cooldown alarm state (see "Reading an alarm"). Use
  `/tw debug set harvestready [--mode=true|false|toggle] [NPC selectors]` to clear
  the configured harvest alarm or restart the role's normal `HarvestTimeout`.
- If prompt behavior is stale/wrong, ensure `TameworkInteractPrompt` is running and use `/tw debug log prompt`.
- If custom item checks fail unexpectedly, verify `ItemsInHand.Operator` (`AnyOf` vs `NoneOf`) and quantity requirements.
- For `NpcHealthPercent` requirements, confirm health scaling assumptions (`0-100`).

## Progression troubleshooting
- Validate resolved configs for happiness/needs/breeding/traits on the same NPC.
- Use:
  - `/tw debug get happiness`
  - `/tw debug get needs --ray`
  - `/tw debug get traits`
  - `/tw debug get lifestage`
  - `/tw debug set lifestage <baby|adolescent|adult|prime|senior> [NPC selectors]`
  - `/tw debug set harvestready [--mode=true|false|toggle] [NPC selectors]`
- Setting `baby` or `adolescent` requires an enabled offspring lifecycle for the
  selected NPC. Setting `prime` requires enabled adult aging, and `senior` also
  requires the Full aging mode.
- For breeding issues, confirm:
  - effective fertility threshold
  - life-stage/adult gates
  - sleep/combat gates
  - cooldown state/alarm timing
  - nearby same-type headroom and any direct SimpleClaims breeding limit

## Coop and persistence integrity

- Confirm an enabled `TwCoopConfig` resolves for the exact coop under test.
- Test live NPC intake and live resident release independently.
- Test filled capture-item intake independently. A successful intake empties
  that item and makes the companion a coop resident; an ineligible item, or a
  stale copy of an item, stays untouched.
- Breaking a coop releases its residents beside the block.
- Filled spawner items still release through their normal interaction when the
  targeted block is not a supported managed coop intake.
- For death or Lost recovery, verify the linked panel shows the recorded state
  and exact cooldown. Roster-backed paid revival must show every configured
  cost; legacy item-linked restoration remains free.
- Tamework 5.0 imports a 3.x or 4.x world by itself at the first start and
  never changes the old files. A 2.x world must run Tamework 4.3.x once first.
  The wiki page "World Migration for Server Admins"
  (`wiki/Player-Guides/Troubleshooting-and-Glossary/World-Migration-for-Server-Admins.md`)
  covers the console lines, the report file, a failed import and
  `/tw persistence start-fresh`.
- To retest an import, stop the server and delete `universe/Tamework/Companions`
  while the old database files are still in `universe/Tamework/Data`. The next
  start imports again. `meta.json` in the new folder holds the import receipt;
  while it exists no import runs.
- There is no persistence status, detail or export command in 5.0. For
  support, collect the server log and, after an import, the
  `import-report-*.txt` file from `universe/Tamework/Data`.
- Companion store problems show in the server log, such as a failed write or
  an unreadable owner file (moved aside with an `.unreadable-` suffix). A
  failed write logs the WARN `Companion data was not written (<n> owner files
  and <n> snapshots pending); it stays in memory and the write is retried`, at
  most once a minute while writes keep failing. The unwritten data stays in
  memory and the write is retried. The
  Public API
  diagnostics view reports record counts by location, the last flush time,
  the last failure, and unreadable records.
- Tamework 5.0 sends no automatic persistence diagnostic bundles.

## Needs/resource seek troubleshooting
- Confirm seek sensor/action components are in the role/template:
  - `Component_Tamework_Instruction_Needs_Seek_Resource_Sensor`
  - `Component_Tamework_Instruction_Needs_Seek_Resource`
  - `TameworkNeedsResourceConsume`
- If seek loops repeat, review reachable targets and failed-seek cooldown behavior.

## Hook and effect troubleshooting
- `TriggerNpcHook` writes `TameworkHookComponent`; `TameworkHook` consumes it.
- In instruction nodes, use `Sensor` (singular), not `Sensors`.
- Use `/tw debug log hook [on|off]` to inspect hook emit/consume flow.
- `TameworkEffectActive` can validate effect-driven branches; verify `EffectId` and optional `MinRemainingSeconds`.

## Command-item troubleshooting
- Confirm held item resolves to a `TwCommandItemConfig` and includes expected command list.
- If radial UI does not open, ensure secondary interaction uses `CommandId: OpenSelectionMenu`.
- If move/home commands do not move NPCs, verify `Component_Tamework_Instruction_Command_Move` is present.
- For panel confusion, verify mode (`LinkedMode`/`NearbyMode`), filter mode/value, and active/inactive row state.
- For unloaded relocation, use linked panel status + `/tw npc find <uuid>` and check relocation timing config.

## Spawner/naming troubleshooting
- Spawner failures: check role filters, tame/owner policy, range/cooldown, and captured metadata.
- A dead-target capture denial writes the player, target, role, item, exact
  health, and death-component state to the server log.
- `/tw debug log respawn-trace` logs `[tw-respawn-trace]` lines for every
  companion body the restore flows spawn (recall, recover, revive, summon,
  captured-item release, and coop release): the spawn result, probes 250 ms
  and 1000 ms later, the first damage, and cancelled fall damage.
- Newly bred offspring receive brief spawn-time fall protection; a cancelled
  fall appears under `[tw-spawn-protection]`.
- Naming failures: confirm naming config binding and policy (`RequireTamed`, `RequireOwner`, rename/replace limits).

## Population and claim troubleshooting

- `limitPerPlayerOwnedTotal` counts every owned companion: out in a world,
  in capture items, rosters, and coops, dead, and lost.
  `limitPerPlayerDeployedTotal` counts summoned companions, loaded or not.
  Imported companions that 5.0 has not seen yet do not count as deployed. If a
  result looks wrong, check the companion's location in the linked panel
  rather than only nearby live NPCs.
- SimpleClaims affects breeding only through its direct claim-required,
  per-chunk, and total-claim settings.
- SimpleClaims damage protection uses its native tamed-NPC policy. Integration
  errors fail open rather than making companions invulnerable.
- There is no QuestLines bridge. Owner, group, and admission-provider limits
  are checked inside the companion index, not by SimpleClaims.

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

`/tw debug avatar player-model unsafe` temporarily replaces the executing player's `ModelComponent` for isolated
model-swap probes. Non-player models can crash the current client once movement animations update, and
extreme positive scales can produce unstable visuals or physics, so the unsafe token is required. The
requested scale is passed through without clamping to the model asset's authored min/max. With no model id
it tries `Endgame_Pet_Dragon_Frost`; use `reset` to restore the saved player model.

`/tw debug avatar input` logs movement packets, mouse packets, interaction events, and per-tick player
input/state snapshots for the executing player. Use it only during short input experiments; it is intentionally verbose.

`/tw debug log despawn` notes:
- Default (no role filter) tracks all tamed companions.
- You can target a role by name (for example `Rat` or `Tamed_Rat`).
- Use `all` or `clear` to remove a role filter without disabling the toggle.

`/tw debug log harvest` logs optimized harvest cooldown checks, cooldown writes, harvest execution stages,
and container harvest results. It is disabled by default because milk and other harvest interactions
can produce several lines per player attempt.

`/tw debug log xp-events` subscribes through `TameworkApi.events()` and logs each `CompanionXpAwardedEvent`
hit, including source, owner UUID, tool ids, XP, and level delta.
When enabled, it also logs `TameworkHarvestDrop` attempts before the public event exists so rejected harvest
XP can be diagnosed with a reason such as not tamed or owned, disabled harvest XP, or missing drop output.
It also logs one line for every published activity (feed, harvest, cull, breeding, need satisfied, capture,
trough fill, command panel open, tame, revive, summon, recall, summon expiry, avatar flight, combat damage and
combat defeat), with the player credited as its actor:

```
[Tamework XP Event Debug] activity=tamework:harvest operationId=<uuid> companionUuid=<uuid> ownerUuid=<uuid> actorUuid=<uuid> profileId=<id> roleId=<id> toolId=<itemId>
```

A missing value prints as `<null>`; `toolId` is `<null>` for a harvest with no tool. A breeding lists both
parents, comma-joined, in `companionUuid`, `ownerUuid` and `roleId`. Player-only activities (capture, trough
fill, command panel open, avatar flight) name only the actor; an avatar flight line carries its flight config id
as `profileId`. A revive names the reviving player as actor. Combat damage lists the source and the target,
comma-joined, in `companionUuid` and `ownerUuid`; a combat defeat names the defeated entity and its owner, with
the player credited for the defeat as actor. Avatar flight and combat activities are published, and so logged,
only while something subscribes to them (another mod or the API test fixture).

## Fast-forwarding needs

`/tw debug needs advance <seconds> [NPC selectors]` decays the hunger and thirst of the selected NPCs as if
`<seconds>` had passed, so a test does not wait in real time. It works from the console with
`--entity <uuid>`. The owner-presence gate still decides how much of the time counts: the window starts now
and runs forward with the owner's presence as it is now, so an online owner's companion counts all of it and
an offline owner's companion counts only what falls after the offline grace. The per-update catch-up cap does
not apply, needs damage and regen suppression follow as in a normal update, and no clock moves. Seconds are on
the needs config's timer (scaled world time for `WorldTimeScaled` configs) and must be above 0 and at most one
year. The reply, one line per NPC, keeps its `key=value` tokens in every language:

```
Advanced needs for NPC <uuid>: requestedSeconds=600.000 countedSeconds=600.000 hunger=80.000->75.000 thirst=90.000->84.000
```

## Fast-forwarding growth and aging

`/tw debug growth advance <seconds> [NPC selectors]` lets `<seconds>` of real time pass for the growth and aging
of the selected NPCs. It works from the console with `--entity <uuid>`. The owner progression clock's offline rule
decides how much counts, as for the needs fast-forward: an online owner (or an unowned animal) counts all of it,
an offline owner counts only what falls after the offline grace. The counted time is then applied as a growth tick
applies the time owed since its last checkpoint: growth at the animal's current hunger and thirst, adult aging,
and the animal's own progression time. That time is also what Tamework's harvest alarm and breeding cooldown are
judged on, so both move by the same amount. Old-age death and the lifecycle role change at a stage change happen
as they would in real time, and the result is saved with the entity. Needs do not decay; run
`/tw debug needs advance` as well when a test needs both. Seconds must be above 0 and at most one year. One line
per NPC:

```
ok entity=<uuid> requestedSeconds=3600.000 countedSeconds=3600.000 stage=Baby->Adult role=Cow_Calf roleChange=requested oldAgeDeath=false
refused reason=no-life-stage entity=<uuid>
refused reason=invalid-seconds max=31536000.000
```

`role` is the role before the change: a requested role change lands on a later tick (check it with
`/tw npc find <uuid>`). The engine's own `Breeding_Cooldown` NPC alarm runs on world time and does not move.

## Moving an owner's offline grace

`/tw debug owner offline-age <ownerUuid> <seconds>` moves an offline owner's offline start `<seconds>` earlier in
both owner clocks: the needs presence timeline and the progression clock that drives growth, aging and production.
A test can then reach the offline grace (72 hours by default) without waiting. Time already counted stays as it
was, and the moved-back span is never credited. Both clocks are shared by all of that owner's animals, and the
progression clock is saved to disk, so the change survives a restart. When the owner shares animals, the owner's
shared presence id, which drives the shared ones, moves too while no player it covers is online. Works from the
console:

```
ok owner=<uuid> movedSeconds=259200.000 presenceOfflineSeconds=259260.000 progressionOfflineSeconds=259260.000
ok owner=<uuid> movedSeconds=259200.000 presenceOfflineSeconds=... progressionOfflineSeconds=... sharedId=<uuid> shared=online
refused reason=owner-online owner=<uuid>
```

## Reading an alarm

`/tw debug get alarm [AlarmName] [NpcUuid]` prints one line and changes nothing. Without a name it reads the
configured harvest alarm (`TwGlobalConfig` `HarvestAlarmName`, `Harvest_Ready` by default). Tamework's own alarm
is read first and judged on the animal's progression time, as harvest readiness is (`source=tamework`;
`exists=false` means no alarm was set, so it is ready). Alarms Tamework does not keep come from the engine's NPC
alarm store on world game time (`source=engine`). From the console give the NPC UUID (and `--world` when it is not
in the default world).

```
ok entity=<uuid> alarm=Harvest_Ready source=tamework ready=false exists=true untilMs=1234567 nowMs=1200000 remainingMs=34567
```

## Reading a companion

`/tw debug get companion [--entity <uuid>]` prints one line with the companion's state, read from memory on its
world thread, and changes nothing. A key the animal has no state for is left out (no `level` when leveling is off,
no `hunger` without enabled needs, no `breedingReady` without breeding). `sharers` lists the players the owner
shares with by name grant; `shareGroups` appears when the owner shares with a group, and `private=true` when the
owner keeps this companion private. `xp` is the XP into the current level. `harvestReady` reads Tamework's harvest
alarm as `get alarm` does; breeding readiness is judged on the animal's own progression time.

```
ok entity=<uuid> role=Cow tamed=true owner=<uuid> sharers=<uuid>,<uuid> stage=Adult level=3 xp=12.00 totalXp=112.00 happiness=72.50 hunger=80.00 thirst=64.00 harvestReady=false harvestRemainingMs=34567 breedingReady=true breedingCooldownRemainingMs=0 traits=Speed:1.050,Size:0.980
refused reason=entity-required option=--entity
```

The NPC debug getters and setters `get companion|happiness|lifestage|owner|role|tamed|traits` and
`set happiness|level|owner|tamed|traits|trait` take `--entity <uuid>` (and `--world <name>` when the NPC is not in
the default world), so they work from the console; a player who gives no `--entity` still targets the NPC in view.
Put `--entity` after the command's own arguments: `tw debug set level 5 --entity <uuid>`. From the console the
setters reply with one line:

```
ok entity=<uuid> requested=80.00 happiness=80.00 breedingReady=true
ok entity=<uuid> requested=5 level=1->5 maxLevel=25 totalXp=400
ok entity=<uuid> owner=<uuid>
ok entity=<uuid> tamed=false->true
ok entity=<uuid> traits=Speed:1.050,Size:0.980 clamped=false
refused reason=owner-required usage=<ownerUuid|name|clear>
refused reason=unknown-owner value=<value>
```

`set owner` takes a UUID (the owner may be offline), an online player's name, or `clear`; a player who names no
owner becomes it. The owner write goes through the ownership systems a tame uses, so the companion index and
population admission see it.

## Console setup of owned and shared animals

`/tw npc spawn tamed <Role> [count] --owner <uuid|name> --at <x> <y> <z> [--world <name>]` spawns owned, tamed
animals for that owner without a player. The owner may be offline when given by UUID. It runs the player spawn's
steps: the owner's population limits, then the owner component write and tamed state that the ownership systems
register and admit, as for a player. Nothing is linked to a command item, and the animals face and follow the owner
only when the owner is online in that world. Without `--owner` a player owns what they spawn, in front of them, as
before. The reply lists every spawned NPC:

```
ok role=Cow owner=<uuid> entity=<uuid>,<uuid> world=default x=10.00 y=64.00 z=-4.00
ok role=Cow owner=<uuid> entity=<uuid> world=default x=10.00 y=64.00 z=-4.00 stopped=population-cap spawned=1 requested=3
refused reason=population-cap role=Cow owner=<uuid> spawned=0 requested=1
refused reason=owner-required option=--owner
refused reason=position-required option=--at
```

`/tw debug share add <player> <caretaker|handler|manager> --owner <uuid|name>`,
`/tw debug share remove <player> --owner <uuid|name>` and `/tw debug share list --owner <uuid|name>` are admin
forms of `/tw share add|remove|list`.
They call the same sharing service as the owner's commands, skip the owner's confirmation, and work from the
console. A share grant records no actor, so each change is written to the server log with the admin who made it.
The player named by `add` is told as if the owner had shared.

```
ok owner=<uuid> player=<uuid> level=handler result=changed new=true
ok owner=<uuid> player=<uuid> result=removed
ok owner=<uuid> players=<uuid>:handler groups=none privateCompanions=0
refused reason=too-many-players owner=<uuid> player=<uuid>
refused reason=not-shared owner=<uuid> value=<value>
```

## Test hooks

| What | Command | Example reply |
|---|---|---|
| Fast-forward growth and aging, through the owner clock's offline rule (also moves harvest and breeding deadlines) | `tw debug growth advance <seconds> --entity <uuid>` | `ok entity=<uuid> requestedSeconds=3600.000 countedSeconds=3600.000 stage=Baby->Adult role=Cow_Calf roleChange=requested oldAgeDeath=false` |
| Fast-forward needs, through the owner-presence gate | `tw debug needs advance <seconds> --entity <uuid>` | `Advanced needs for NPC <uuid>: requestedSeconds=600.000 countedSeconds=600.000 hunger=80.000->72.000 thirst=90.000->84.000` |
| Move an offline owner's offline start back (shared by all that owner's animals, saved) | `tw debug owner offline-age <ownerUuid> <seconds>` | `ok owner=<uuid> movedSeconds=259200.000 presenceOfflineSeconds=259260.000 progressionOfflineSeconds=259260.000` |
| Read harvest readiness without changing it | `tw debug get alarm <uuid> [AlarmName]` | `ok entity=<uuid> alarm=Harvest_Ready source=tamework ready=false exists=true untilMs=... nowMs=... remainingMs=34567` |
| Read a companion: owner, sharers, stage, level, XP, happiness, needs, harvest and breeding readiness, traits | `tw debug get companion --entity <uuid>` | `ok entity=<uuid> role=Cow tamed=true owner=<uuid> sharers=none stage=Adult level=3 xp=12.00 totalXp=112.00 happiness=72.50 hunger=80.00 thirst=64.00 harvestReady=true harvestRemainingMs=0 breedingReady=false breedingCooldownRemainingMs=420000 traits=none` |
| Read happiness, life stage, owner, role, tamed, traits (human-readable lines) | `tw debug get happiness\|lifestage\|owner\|role\|tamed\|traits --entity <uuid>` | `Owner for NPC <uuid> is <uuid>` |
| Own an animal without a player (setup; offline owner allowed by UUID) | `tw npc spawn tamed <Role> [count] --owner <uuid\|name> --at <x> <y> <z> [--world <name>]` | `ok role=Cow owner=<uuid> entity=<uuid> world=default x=10.00 y=64.00 z=-4.00` |
| Set or clear an owner (setup) | `tw debug set owner <ownerUuid\|name\|clear> --entity <uuid>` | `ok entity=<uuid> owner=<uuid>` |
| Force happiness, level, tamed, traits (setup) | `tw debug set happiness <value>\|level <level>\|tamed [true\|false]\|traits <Id> <Value> ...\|trait <Id> <Value> --entity <uuid>` | `ok entity=<uuid> requested=5 level=1->5 maxLevel=25 totalXp=400` |
| Share an owner's animals with a player (setup, no confirm step; logged with the admin) | `tw debug share add <player> <caretaker\|handler\|manager> --owner <uuid\|name>`, `tw debug share remove <player> --owner ...` | `ok owner=<uuid> player=<uuid> level=handler result=changed new=true` |
| Read what an owner shares | `tw debug share list --owner <uuid\|name>` | `ok owner=<uuid> players=<uuid>:handler groups=none privateCompanions=0` |
| Read needs | `tw debug get needs --entity <uuid>` | `Needs for NPC <uuid>: hunger=72.00, thirst=70.00, ...` |
| Read NPC state, position, leash | `tw debug get wander <role filter>` | `<uuid> role=Cow state=Idle pos=1.0,64.0,2.0 leash=... inWater=false` |
| Read role, stage, position | `tw npc find <uuid>` | `NPC <uuid> found: role=..., pos=..., stage=Adult, ...` |
| Force needs (setup) | `tw debug set hunger\|thirst\|needs ... --entity <uuid>` | |
| Force harvest readiness (setup: a step reached this way is UNTESTED) | `tw debug set harvestready --mode=true\|false\|toggle --entity <uuid>` | `Set harvest readiness for NPC <uuid>: false -> true (alarm Harvest_Ready).` |
| Force breeding readiness, life stage (setup) | `tw debug set breedingready --entity <uuid>`, `tw debug set lifestage <stage> --entity <uuid>` | `Set life stage for NPC <uuid>: Baby -> Adult.` |
| Force NPC state (setup) | `tw debug set state <uuid> <State>` | `Set NPC <uuid> to state <State>.` |
| Grant a stored bonded companion (player online) | `tw bonded grant <player\|uuid> <rosterId> <roleId> [name]` | |
| Log published activities | `tw debug log xp-events on` | one `[Tamework XP Event Debug] activity=...` log line per activity, with owner and actor |
| Player sender only (send as a bot) | `tw share ...` (the caller's own grants), `tw debug get flock`, `tw debug view ...`, `tw debug avatar ...`, `tw config open`, `tw settings`, `tw card`, `tw companions restore`, `tw api test prepare\|reset` | console: `Sender must be a player or provide the --player option!` |
| Needs a player only without `--entity` / `--owner` | the NPC getters and setters above without `--entity`; `tw npc spawn tamed <Role>` without `--owner` | console: `refused reason=entity-required option=--entity`, `refused reason=owner-required option=--owner` |

## Useful quick checks
- `/tw debug get owner`, `/tw debug set owner`
- `/tw debug get tamed`, `/tw debug set tamed`
- `/tw debug get alarm [AlarmName] [NpcUuid]`
- `/tw debug get flock`
- `/tw npc clean <roleId>`
- `/tw config reload` (item-feature assets only)

## Timestamp note
World-time based timestamps can be negative and still valid. Treat `0` as unset sentinel; use ordering comparisons, not `> 0` assumptions.
