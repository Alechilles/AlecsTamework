# Debugging and Testing

## Recommended workflow
- Test on a local server first.
- Check server logs for builder or asset errors.
- Validate spawner and interaction configs are loading.

## Common log messages
- "Builder ... does not exist" -> missing action or sensor registration or asset load order.
- "Unknown JSON attribute ..." -> builder does not recognize the field name.
- "TameworkInteract: no config resolved or config disabled" -> role id mismatch or config disabled.
- "TameworkInteract: no interactions matched" -> requirements did not pass. The log includes a summary of key flags.
- "Mount preset effect not yet implemented" -> feature stub.

## Interaction troubleshooting
- Verify `TwInteractionConfig` is enabled and `RoleIds` include the NPC role id.
- If multiple configs match the same role, use `ConfigId` on the action to force the selection.
- Ensure role parameters exist if you rely on them (names are defined by `TwGlobalConfig`, defaults are `LovedItems`, `IsMountable`, `IsHarvestable`, `HarvestInteractionContext`).
- If harvest always works, make sure your role sets the `TwGlobalConfig.HarvestAlarmName` alarm when `$Harvest` runs (default `Harvest_Ready`).
- If harvest never works, check `HarvestInteractionContext` (or your custom `HarvestContextParam`) and the harvest alarm state.
- Cooldowns are enforced in real-time seconds and stored as alarms. Use `/tw getalarm` to inspect them if interactions seem locked out.
  The alarm prefix is `TwGlobalConfig.InteractionCooldownAlarmPrefix`.
- If prompts look wrong or stale, ensure `TameworkInteractPrompt` is running and use `/tw debugprompt` to inspect prompt selection.
- Prompt hint keys are `server.interactionHints.*` and must be defined in `Server/Languages/en-US/server.lang` without the `server.` prefix.

## Hook troubleshooting
- `TriggerNpcHook` writes a `TameworkHookComponent` to the NPC.
- `TameworkHook` sensors can consume the hook automatically if `Consume` is true.
- In NPC instructions, use `Sensor` (singular). `Sensors` is not supported and will default to always-match behavior.
- Use `/tw debughook [on|off]` to enable hook debug logs (or toggle without args).
- Use debug logs or temporary particles to verify the hook was emitted and consumed.

## Command-item troubleshooting
- Confirm your item is present in a `TwCommandItemConfig.ItemIds` list.
- If secondary-use radial selection does not open, verify the item has a `TameworkCommand` interaction with `CommandId: OpenSelectionMenu`.
- If move/home commands select but do not move, ensure your role/template includes `Component_Tamework_Instruction_Command_Move`.
- If unloaded linked NPCs are not relocating, verify the tool still contains linked NPC metadata and check command feedback for queued counts.
- If recall/return-home behavior feels wrong at distance, review `TwGlobalConfig` command relocation tuning fields.
- If command linking fails unexpectedly, verify owner/tamed requirements and `AllowedRoles` filters in `TwCommandItemConfig`.

## Visual debugging tips
- Enable NPC debug options such as `DisplayState` and `DisplayTarget` to see live behavior state and targeting.
- Add temporary particles or sounds inside instructions to confirm sensor triggers.

## Useful checks
- Spawn both Tamework example mobs to validate actions and sensors.
- Confirm capture and spawn preserves attachments.
- Validate owner and tamed gating with `/tw getowner` and `/tw gettamed`.
- Check alarm state + remaining cooldown with `/tw getalarm [AlarmName]`.
- Toggle spawner raycast logs with `/tw debugspawner` when testing spawner items.
- After editing spawner, naming, or command item configs, run `/tw reloadconfig`.
## Global config warnings
- If `TwGlobalConfig` is missing required fields, the server logs a warning listing which fields are blank.

## Hytalor patches
- Hytalor writes patched output to `.../Server/mods/HytalorOverrides/`.
- If a change seems ignored, compare the override file to your patch source to verify the patch applied.
