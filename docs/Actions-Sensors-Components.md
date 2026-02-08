# Actions, Sensors, and Components

This file is a quick map of the custom behavior surface area.

## Actions
- Capture actions:
  - owner/stranger/wild capture flows
  - deny capture when untamed (optional foods list)
- Tamed state:
  - `TameworkSetTamed` sets a boolean on the NPC
- Owner setting:
  - owner component is set from player context

## Sensors
- Owner sensors:
  - is owner / has owner / no owner
- Tamed sensor:
  - is tamed

## Components

- **Owner component**
  - stores owner UUID and name
- **Tamed component**
  - stores tamed boolean

## Notes
- Components persist across reloads.
- Avoid custom logic in templates when a Tamework action exists.

## Commands
- `/tw getowner` prints the owner name + UUID (when available).
- `/tw gettamed` prints the current tamed status for the targeted NPC.
- `/tw settamed` toggles the tamed flag on the targeted NPC.
- `/tw reloadconfig` reloads Tamework item feature configs from disk.

