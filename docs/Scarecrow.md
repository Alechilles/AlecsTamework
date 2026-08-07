# Scarecrow Spawn Suppression

`Tamework_Scarecrow` is a placeable entity that uses Hytale's native spawn-suppression system. While it remains in the world, ordinary world NPC spawning and automatic spawn-marker spawning are blocked within 32 blocks.

## Player behavior

- Use the scarecrow item on the top of a solid block to place it.
- Use the placed scarecrow to return it to your inventory. If the inventory cannot accept it, the scarecrow remains in place.
- Multiple scarecrows may overlap. Removing one does not disable another suppressor covering the same area.
- Existing NPCs are not despawned or otherwise changed.

The scarecrow does not block manual spawn-marker triggers or deliberate spawns such as commands, Tamework spawner items, breeding, recalls, or scripted integrations.

## Implementation notes

The placed entity carries Hytale's serialized `SpawnSuppressionComponent` with the `Tamework_Scarecrow` suppression asset. The component is present before the entity is added with `AddReason.SPAWN`, and collection removes the entity with `RemoveReason.REMOVE` so native marker suppression is released correctly.

The item currently inherits the stock `Deco_Scarecrow` model, texture, icon, and hitbox. Those visuals are intentionally a replaceable placeholder; no custom model assets are shipped yet.
