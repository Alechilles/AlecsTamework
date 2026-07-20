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
  - `/tw debug get needs --ray`
  - `/tw gettraits`
  - `/tw getlifestage`
- For breeding issues, confirm:
  - effective fertility threshold
  - life-stage/adult gates
  - sleep/combat gates
  - cooldown state/alarm timing
  - whether `/tw gethappiness` reports an active/latest job, including its id, state, mode, partner, planned/admitted/outstanding/exact-spawned counts, population headroom, terminal reason, and rollback-attempt result

## Managed-coop and persistence integrity
- `/tw coop audit` summarizes active managed authorities and prints bounded per-resident/per-operation identity details: profile, slot, source/projection UUIDs, operation state, generation, retries, and queue lifecycle state.
- `/tw coop import-status` prints the concise legacy-resident import view: active sessions, pending sources, sources awaiting exact absence proof, and unresolved conflicts.
- `/tw coop reconcile <x> <y> <z>` prints the cached report for that exact coop. It is report-only until an authorized operator repeats the exact fingerprint with `confirm <fingerprint>`; `cancel` revokes process-local approval. A changed report or restart requires a new confirmation.
- `/tw coop rollback-preflight` is read-only. It reports queue, integrity, active lifecycle/import work, and older SQLite snapshot evidence while explicitly rejecting unsafe live downgrade claims. Tamework never creates or restores whole-save backups; any full rollback is an operator-managed Hytale/host procedure using mutually consistent world and Tamework data.
- `/tw debugdb integrity` runs the SQLite/foreign-key checks plus canonical identity, managed-coop lifecycle, and import-journal invariants.
- An import marked `attention required` is intentionally fail-closed. Preserve the database and save evidence; do not clear or respawn residents merely to make the count disappear.
- A coop is Tamework-authoritative only when an enabled `TwCoopConfig` resolves for that exact coop id. Unmanaged coops remain vanilla and are not shadowed by a Tamework resident sidecar.

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
- Positive owner or claim admissions stay fail-closed until the required reconciliation dimensions are `READY`. Startup resumes the same persisted `ACTIVE` scan epoch and source cursors after a restart, and it asks Hytale to load saved worlds before catalog construction. A failed world load or changed mutable source keeps coverage unsealed instead of publishing false readiness.
- Claim providers are re-probed from live plugin state per operation. Reflected ready contracts are weak and generation-bound; plugin setup/reload, replacement, disable, or `/tw settings` changes invalidate stale sessions. An incompatible or incomplete QuestLines `getChunks()` contract is an admission error, not a SimpleClaims fallback condition.
- `Unavoidable companion relocation created a per-world owner over-cap condition` means a cross-world move was preserved even though the destination now exceeds its per-world owner cap. The warning is throttled, `unavoidablePerWorldOverCapRelocations` increments, and later positive admissions remain blocked until the count falls.
- Population-bearing world work uses a lease-aligned start watchdog. If an accepted callback never starts during shutdown, its rejection cleanup runs exactly once and any late queued wrapper is inert. Repeated warnings here usually indicate world shutdown or executor backlog, not a second mutation.
- `/tw api test prepare` and `/tw api test reset` use production journaled `ADMIN_FORCE` assignment and permanent-release authority. A readiness, admission, or durability failure from these commands is therefore meaningful and should not be bypassed with direct owner/profile edits.

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
- `/tw coop audit`, `/tw coop import-status`, `/tw coop reconcile`, `/tw coop rollback-preflight`
- `/tw debugdb integrity`
- `/tw npcclean <roleId>` removes only matching NPCs proven unowned. It is unavailable until both owner-population and claim-occupancy reconciliation are `READY`, and it skips canonical identities with an owner or pending transition.
- `/tw reloadconfig` (item-feature assets only)

## Timestamp note
World-time based timestamps can be negative and still valid. Treat `0` as unset sentinel; use ordering comparisons, not `> 0` assumptions.
