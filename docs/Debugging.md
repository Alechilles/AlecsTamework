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
includes `/tw patches status` and the server-global `debug*` logging toggles.
`/tw debugcrashtelemetry` status and `flush` are also console-safe; its simulated
event/crash actions remain restricted to the existing allowlisted player identities.
`/tw debugdb [status|health|integrity|detail]` is console-safe and read-only.

Commands that operate on a world but not a player use Hytale's optional world
argument. Console callers must provide the target world for `/tw reloadconfig`,
`/tw patches reload`, `/tw npcclean`, `/tw findnpc`, and `/tw getalarm`. The last
two require an NPC UUID when no player gaze target exists; player-relative distance
is reported as `n/a` from the console.

Player UI, held-item, gaze-only, player-overlay, and live API fixture commands remain
player-scoped. In particular, `/tw config`, `/tw settings`, `/tw news`,
`/tw api test prepare|reset|run|status`, `/tw spawntamed`, `/tw showhitboxes`,
and `/tw showspawnmarkers` need a live player.

## Interaction troubleshooting
- Verify matching enabled `TwInteractionConfig` with expected `RoleIds` and `Priority`.
- If multiple configs apply, set explicit `ConfigId` on `TameworkInteract` for deterministic selection.
- Confirm role params referenced by `TwGlobalConfig.InteractionDefaults` exist and have expected values.
- Use `/tw getalarm` for harvest/cooldown alarm state.
- If prompt behavior is stale/wrong, ensure `TameworkInteractPrompt` is running and use `/tw debugprompt`.
- If custom item checks fail unexpectedly, verify `ItemsInHand.Operator` (`AnyOf` vs `NoneOf`) and quantity requirements.
- For `NpcHealthPercent` requirements, confirm health scaling assumptions (`0-100`).

## Progression troubleshooting
- Validate resolved configs for happiness/needs/breeding/traits on the same NPC.
- Use:
  - `/tw gethappiness`
  - `/tw debug get needs --ray`
  - `/tw gettraits`
  - `/tw getlifestage`
- For breeding issues, confirm:
  - effective fertility threshold
  - life-stage/adult gates
  - sleep/combat gates
  - cooldown state/alarm timing
  - nearby same-type headroom and any direct SimpleClaims breeding limit

## Coop and persistence integrity

- Confirm an enabled `TwCoopConfig` resolves for the exact coop under test.
- Test live NPC intake and live resident release independently.
- Captured items are not a coop-intake path.
- Filled spawner items release their stored NPC through the normal spawner
  interaction.
- For death or Lost recovery, verify the linked panel shows the recorded state
  and that restoration is free.
- Released schema v2-v4 sources and released DAT records import into
  `tamework-state.sqlite` without modifying the source. A v5-v9 source is a
  deliberate refusal: restore a public backup or create a new world instead of
  trying to repair or migrate that tester-only database.
- Use `/tw debugdb status` for engine, target-origin, schema, startup, operation,
  validation, and checkpoint state. `health` and `integrity` are aliases for
  the same bounded summary.
- Use `/tw debugdb detail` for bounded feature, outbox, operation-phase,
  incident, quarantine, and `openCircuits` counts. It does not repair or retry
  persistence work. Circuit evidence comes from the one replacement feature
  registry and shared `feature_circuit` table, not an old failure catalog;
  there is no separate persistence rehearsal runtime.

## Needs/resource seek troubleshooting
- Confirm seek sensor/action components are in the role/template:
  - `Component_Tamework_Instruction_Needs_Seek_Resource_Sensor`
  - `Component_Tamework_Instruction_Needs_Seek_Resource`
  - `TameworkNeedsResourceConsume`
- If seek loops repeat, review reachable targets and failed-seek cooldown behavior.

## Hook and effect troubleshooting
- `TriggerNpcHook` writes `TameworkHookComponent`; `TameworkHook` consumes it.
- In instruction nodes, use `Sensor` (singular), not `Sensors`.
- Use `/tw debughook [on|off]` to inspect hook emit/consume flow.
- `TameworkEffectActive` can validate effect-driven branches; verify `EffectId` and optional `MinRemainingSeconds`.

## Command-item troubleshooting
- Confirm held item resolves to a `TwCommandItemConfig` and includes expected command list.
- If radial UI does not open, ensure secondary interaction uses `CommandId: OpenSelectionMenu`.
- If move/home commands do not move NPCs, verify `Component_Tamework_Instruction_Command_Move` is present.
- For panel confusion, verify mode (`LinkedMode`/`NearbyMode`), filter mode/value, and active/inactive row state.
- For unloaded relocation, use linked panel status + `/tw findnpc <uuid>` and check relocation timing config.

## Spawner/naming troubleshooting
- Spawner failures: check role filters, tame/owner policy, range/cooldown, and captured metadata.
- Naming failures: confirm naming config binding and policy (`RequireTamed`, `RequireOwner`, rename/replace limits).

## Population and claim troubleshooting

- The owner cap counts loaded NPCs currently owned by that player. If the result
  looks wrong, inspect the live owner component and count other loaded owned
  NPCs.
- SimpleClaims affects breeding only through its direct claim-required,
  per-chunk, and total-claim settings.
- SimpleClaims damage protection uses its native tamed-NPC policy. Integration
  errors fail open rather than making companions invulnerable.
- There is no provider selector, QuestLines bridge, durable dormant count,
  placement reservation, or batch-admission diagnostic to inspect.

## Debug toggles
- `/tw debughook [on|off]`
- `/tw debugprompt [on|off]`
- `/tw debugspawner [on|off]`
- `/tw debugspawnerlocation [on|off]`
- `/tw debugdespawn [on|off] [RoleName|all|clear]`
- `/tw debugplayermodel unsafe [ModelId] [scale] | reset | status`
- `/tw debugplayerinput [on|off|status]`
- `/tw debuglag [on|off]`
- `/tw debugharvest [on|off]`
- `/tw debugxpevents [on|off]`

`/tw debugplayermodel unsafe` temporarily replaces the executing player's `ModelComponent` for isolated
model-swap probes. Non-player models can crash the current client once movement animations update, and
extreme positive scales can produce unstable visuals or physics, so the unsafe token is required. The
requested scale is passed through without clamping to the model asset's authored min/max. With no model id
it tries `Endgame_Pet_Dragon_Frost`; use `reset` to restore the saved player model.

`/tw debugplayerinput` logs movement packets, mouse packets, interaction events, and per-tick player
input/state snapshots for the executing player. Use it only during short input experiments; it is intentionally verbose.

`/tw debugdespawn` notes:
- Default (no role filter) tracks all tamed companions.
- You can target a role by name (for example `Rat` or `Tamed_Rat`).
- Use `all` or `clear` to remove a role filter without disabling the toggle.

`/tw debugharvest` logs optimized harvest cooldown checks, cooldown writes, harvest execution stages,
and container harvest results. It is disabled by default because milk and other harvest interactions
can produce several lines per player attempt.

`/tw debugxpevents` subscribes through `TameworkApi.events()` and logs each `CompanionXpAwardedEvent`
hit, including source, owner UUID, tool ids, XP, and level delta.
When enabled, it also logs `TameworkHarvestDrop` attempts before the public event exists so rejected harvest
XP can be diagnosed with a reason such as not tamed or owned, disabled harvest XP, or missing drop output.

## Useful quick checks
- `/tw getowner`, `/tw setowner`
- `/tw gettamed`, `/tw settamed`
- `/tw getalarm [AlarmName] [NpcUuid]`
- `/tw getflockdebug`
- `/tw npcclean <roleId>`
- `/tw reloadconfig` (item-feature assets only)

## Timestamp note
World-time based timestamps can be negative and still valid. Treat `0` as unset sentinel; use ordering comparisons, not `> 0` assumptions.
