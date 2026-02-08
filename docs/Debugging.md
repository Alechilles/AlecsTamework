# Debugging and Testing

## Recommended workflow
- Test on a local server first.
- Check server logs for builder/action errors.
- Validate config discovery (mod defaults + per‑world overrides).

## Common log messages
- “Builder ... does not exist” → missing action/sensor registration or asset load order.
- “Unknown JSON attribute ...” → action builder doesn’t recognize the field name.
- Owner/tamed issues → ensure components are attached and actions are firing.

## Visual debugging tips
- Enable NPC debug options such as **DisplayState**, **DisplayTarget**, and related flags to see live behavior state and targeting.
- Add temporary particle spawns inside instructions/actions to confirm sensor triggers or action chain steps (e.g., small poof on sensor match, different colors per branch).
- Keep these effects lightweight and remove them once the behavior is verified.

## Useful checks
- Spawn both tamework example mobs to validate actions/sensors.
- Confirm capture + spawn preserves attachments.
- Validate owner/tamed gating with `/tw getowner` and denial messages.
- After editing `Tamework_Items_Config.json`, run `/tw reloadconfig` to reload item configs.

## Hytalor patches
- Hytalor writes patched output to `.../Server/mods/HytalorOverrides/`.
- If a change seems ignored, compare the override file to your patch source to verify the patch applied.

