# Scarecrow Spawn Suppression

`Tamework_Scarecrow` is a placeable block that uses Hytale's native spawn-suppression system. Automatic spawn-marker spawning is blocked within an exact 32-block three-dimensional radius. Ordinary world spawning is blocked in the native spawn chunks intersecting that area, so its horizontal edge can extend to the boundary of an affected chunk.

## Player behavior

- Place and rotate the scarecrow with Hytale's normal block-placement controls.
- Break the scarecrow normally to pick it back up.
- Multiple scarecrows may overlap. Removing one does not disable another suppressor covering the same area.
- Existing NPCs are not despawned or otherwise changed.
- Normal block placement, breaking, and claim rules apply.

The scarecrow does not block manual spawn-marker triggers or deliberate spawns such as commands, Tamework spawner items, breeding, recalls, or scripted integrations.

## Implementation notes

Native block place and break events maintain one invisible, serialized entity beside each scarecrow block. That entity carries Hytale's `SpawnSuppressionComponent` with the `Tamework_Scarecrow` suppression asset. It is added with `AddReason.SPAWN` and removed with `RemoveReason.REMOVE` so the native suppression controller is updated correctly.

The item inherits the stock `Deco_Scarecrow` model, hitbox, gathering behavior, and placement rotations. Its custom texture changes the stock blue hat to purple.
