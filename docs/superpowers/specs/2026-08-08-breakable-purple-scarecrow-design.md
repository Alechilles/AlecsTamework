# Breakable Purple Scarecrow Design

## Goal

Replace new entity-rendered scarecrow placements with normal Hytale blocks so the placement preview matches the result and players can remove them through ordinary block breaking. Give the Tamework scarecrow a distinct purple hat while preserving the vanilla scarecrow's remaining texture.

## Asset Design

- Keep `Tamework_Scarecrow` as a child of `Deco_Scarecrow`.
- Add a mod-local 128x128 texture copied from Hytale 0.5.7's `Common/Blocks/Farming/Scarecrow_Texture.png`.
- Recolor only the blue pixels used by the hat UV islands to purple. Preserve the blue buttons, brown hat band, buckle, clothing, wood, alpha, dimensions, and pixel-art shading.
- Override the child block type's `CustomModelTexture` with the mod-local texture while continuing to inherit the vanilla model, hitbox, gathering, sounds, and four-direction rotation.
- Remove the custom item-placement interaction and block interaction hint so native block placement and breaking own the player-facing lifecycle.

## Runtime Design

Hytale 0.5.7 stores breakable blocks in `ChunkStore`, but `SpawnSuppressionComponent` is an `EntityStore` component consumed by `SpawnSuppressionSystems.Suppressor`. The implementation will therefore pair each real scarecrow block with one invisible suppression entity.

Two focused entity event systems will bridge the lifecycle:

1. On `PlaceBlockEvent`, recognize the held `Tamework_Scarecrow` item, copy the target coordinates, and enqueue world-thread reconciliation. The deferred callback verifies that placement actually succeeded and that the block at the target is `Tamework_Scarecrow` before ensuring one suppression entity exists at its center.
2. On `BreakBlockEvent`, recognize the `Tamework_Scarecrow` block and enqueue world-thread reconciliation. The deferred callback verifies that the block is gone before removing matching Tamework suppression entities at that coordinate.

The deferred checks prevent cancelled or failed place/break attempts from creating or removing suppression. Suppression entities remain serializable so they survive restarts alongside their blocks. Exact-position matching prevents one scarecrow from removing another's suppression.

## Compatibility

Existing entity-rendered scarecrows are not world blocks and cannot be converted safely through asset reload alone. Their existing per-entity Use interaction and one-second collection channel remain registered so players can collect them with the known Use key before replacing them. Newly placed scarecrows use native block placement and breaking only.

## Error Handling

- Do nothing when the world, chunk, block type, or suppression asset is unavailable.
- Do not create duplicates when a matching suppressor already exists.
- Remove every matching suppressor at a broken block coordinate to repair accidental duplicates.
- Keep all entity-store mutation inside a queued world-thread callback or ECS command buffer.

## Verification

- A focused runtime-level service test will cover the coordinate matcher used to associate blocks and suppressors only if it can invoke production behavior and assert an observable selection result.
- Existing scarecrow suppression and legacy collection tests remain valid.
- Run the full Gradle test suite and assemble the packaged mod.
- Inspect the packaged item and texture assets for correct paths, dimensions, and alpha.
- Validate Hytale 0.5.7 Java references with Hytale Workshop and run the repository ECS/thread-safety greps.
- Live verification remains: native preview and rotation, purple-hat rendering, normal block drop, suppression registration after placement, and suppression release after breaking.

## Base-Game Evidence

- Hytale Workshop 0.5.7: `BlockPlaceUtils.placeBlock` invokes `PlaceBlockEvent` before attempting placement, so reconciliation must be deferred and verify the resulting block.
- Hytale Workshop 0.5.7: `BlockHarvestUtils.performBlockBreak` invokes `BreakBlockEvent` before removal, so cleanup must be deferred and verify absence.
- Hytale Workshop 0.5.7: `SpawnSuppressionComponent` implements `Component<EntityStore>` and `SpawnSuppressionSystems.Suppressor` registers entities with Transform, UUID, and suppression components.
- Hytale 0.5.7 asset: `Server/Item/Items/Deco/Deco_Scarecrow.json` provides the inherited model, hitbox, gathering, rotation, and stock texture contract.
