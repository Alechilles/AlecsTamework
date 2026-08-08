# Scarecrow Spawn Suppression

`Tamework_Scarecrow` is a placeable entity that uses Hytale's native spawn-suppression system. Automatic spawn-marker spawning is blocked within an exact 32-block three-dimensional radius. Ordinary world spawning is blocked in the native spawn chunks intersecting that area, so its horizontal edge can extend to the boundary of an affected chunk.

## Player behavior

- Use the scarecrow item on the top of a solid block to place it.
- The scarecrow is centered on the selected solid surface and faces its placer.
- Look at the placed scarecrow and hold the prompted key for one second to return it to your inventory. If the inventory cannot accept it, the scarecrow remains in place.
- Multiple scarecrows may overlap. Removing one does not disable another suppressor covering the same area.
- Existing NPCs are not despawned or otherwise changed.
- The scarecrow is an interactable world prop rather than a block. Block-only claim rules do not automatically gate its placement or collection; servers need permissions that cover custom entity interactions to protect it.

The scarecrow does not block manual spawn-marker triggers or deliberate spawns such as commands, Tamework spawner items, breeding, recalls, or scripted integrations.

## Implementation notes

The placed entity carries Hytale's serialized `SpawnSuppressionComponent` with the `Tamework_Scarecrow` suppression asset. The component is present before the entity is added with `AddReason.SPAWN`, and collection removes the entity with `RemoveReason.REMOVE` so native marker suppression is released correctly.

The item inherits the stock `Deco_Scarecrow` block visual and icon. The placed prop uses that same stock placeholder visual; no custom art is shipped yet.
