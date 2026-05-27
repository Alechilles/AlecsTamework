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
  - `/tw getneeds`
  - `/tw gettraits`
  - `/tw getlifestage`
- For breeding issues, confirm:
  - effective fertility threshold
  - life-stage/adult gates
  - sleep/combat gates
  - cooldown state/alarm timing

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

## Debug toggles
- `/tw debughook [on|off]`
- `/tw debugprompt [on|off]`
- `/tw debugspawner [on|off]`
- `/tw debugspawnerlocation [on|off]`
- `/tw debugdespawn [on|off] [RoleName|all|clear]`
- `/tw debuglag [on|off]`
- `/tw debugxpevents [on|off]`

`/tw debugdespawn` notes:
- Default (no role filter) tracks all tamed companions.
- You can target a role by name (for example `Rat` or `Tamed_Rat`).
- Use `all` or `clear` to remove a role filter without disabling the toggle.

`/tw debugxpevents` subscribes through `TameworkApi.events()` and logs each `CompanionXpAwardedEvent`
hit, including source, owner UUID, tool ids, XP, and level delta.
When enabled, it also logs `TameworkHarvestDrop` attempts before the public event exists so rejected harvest
XP can be diagnosed with a reason such as missing command link, disabled harvest XP, or missing drop output.

## Useful quick checks
- `/tw getowner`, `/tw setowner`
- `/tw gettamed`, `/tw settamed`
- `/tw getalarm [AlarmName] [NpcUuid]`
- `/tw getflockdebug`
- `/tw reloadconfig` (item-feature assets only)

## Timestamp note
World-time based timestamps can be negative and still valid. Treat `0` as unset sentinel; use ordering comparisons, not `> 0` assumptions.
