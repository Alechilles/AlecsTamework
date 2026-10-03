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
`/tw api test prepare|reset|run|status`, `/tw npc spawn tamed`, `/tw debug view hitboxes`,
`/tw debug view spawn-beacons`, and `/tw debug view spawn-markers` need a live player.

## Interaction troubleshooting
- Verify matching enabled `TwInteractionConfig` with expected `RoleIds` and `Priority`.
- If multiple configs apply, set explicit `ConfigId` on `TameworkInteract` for deterministic selection.
- Confirm role params referenced by `TwGlobalConfig.InteractionDefaults` exist and have expected values.
- Use `/tw debug get alarm` for harvest/cooldown alarm state. Use
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
  an unreadable owner file (moved aside with an `.unreadable-` suffix). The
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

## Useful quick checks
- `/tw debug get owner`, `/tw debug set owner`
- `/tw debug get tamed`, `/tw debug set tamed`
- `/tw debug get alarm [AlarmName] [NpcUuid]`
- `/tw debug get flock`
- `/tw npc clean <roleId>`
- `/tw config reload` (item-feature assets only)

## Timestamp note
World-time based timestamps can be negative and still valid. Treat `0` as unset sentinel; use ordering comparisons, not `> 0` assumptions.
