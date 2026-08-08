# Scarecrow Spawn Suppression Design

Date: 2026-08-06

## Objective

Add a placeable scarecrow item using Hytale's native 32-block suppression setting. It must suppress ordinary world spawning and automatic spawn-marker spawning and respawning while leaving existing NPCs and deliberate Tamework spawn paths unchanged. Native marker checks use the exact three-dimensional radius; native world-spawn checks suppress whole intersecting spawn chunks horizontally.

Deliberate Tamework spawn paths include filled spawner-item release, breeding, commands, companion recall, revival, and other explicit projection spawns. Manually triggered Hytale spawn markers are also outside the first version because Hytale 0.5.7 does not apply its native marker-suppression gate inside `SpawnMarkerEntity.trigger(...)`.

## Confirmed Hytale 0.5.7 Foundation

The feature will use Hytale's existing spawn-suppression system rather than intercepting or undoing NPC spawns.

- `com.hypixel.hytale.server.spawning.assets.spawnsuppression.SpawnSuppression` is registered under `Server/NPC/Spawn/Suppression` by `SpawningPlugin.setup`. Its asset fields are `SuppressionRadius`, `SuppressedGroups`, and `SuppressSpawnMarkers`.
- A null or empty `SuppressedGroups` selection suppresses every automatic NPC role. The scarecrow asset will use that behavior.
- `com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionComponent` identifies an EntityStore entity as a suppressor. Hytale's suppressor system requires the suppression component, `TransformComponent`, and `UUIDComponent`.
- `SpawnSuppressionSystems.Suppressor` registers and removes suppressors, updates the chunk suppression map, and applies or releases marker suppression using the suppressor UUID.
- `SpawnSuppressionController` persists suppressor identity and position and rebuilds suppression on world load.
- `WorldSpawnJobSystems` applies `SuppressionSpanHelper` before normal light, block, fluid, breathing, geometry, and position checks, so suppressed world-spawn jobs never create an NPC.
- `SpawnMarkerSuppressionSystem` and `SpawnSuppressionSystems.suppressSpawns(...)` add suppressor UUIDs to nearby `SpawnMarkerEntity` instances. `SpawnMarkerSystems.Ticking.tick(...)` returns while that set is non-empty, pausing automatic initial spawns and respawns.
- `SpawnMarkerEntity.trigger(...)` bypasses `suppressedBy`. Blocking manually triggered markers would require a separate interception boundary and is intentionally excluded.

Hytale includes the same asset pattern in `Server/NPC/Spawn/Suppression/Spawn_Camp.json`, among other suppression assets. Hytale's `SpawnSuppressionCommand.Add.execute(...)` provides the authoritative entity-construction pattern.

## User-Visible Behavior

### Placement

The player uses `Tamework_Scarecrow` on the top of a loaded solid surface. A native deployable preview shows the stock placeholder model at its exact final position and continuous yaw, and placement preserves that preview transform.

Before consuming the item, placement validates all of the following:

- the target world and chunk are still valid;
- the `Tamework_Scarecrow` suppression asset resolves;
- the target surface and volume can hold the scarecrow;
- the entity holder can be queued through the current `CommandBuffer`.

Successful placement creates one visible, persistent prop entity and then consumes one item outside creative mode. Failed placement leaves the item unchanged and sends brief player feedback.

### Suppression

The entity carries `SpawnSuppressionComponent("Tamework_Scarecrow")`. The suppression asset uses:

```json
{
  "SuppressionRadius": 32.0,
  "SuppressedGroups": [],
  "SuppressSpawnMarkers": true
}
```

The result is:

- new ordinary world NPC spawns are rejected in every native spawn chunk intersecting the radius horizontally, with the configured numeric span retained vertically;
- automatic spawn-marker initial spawns and respawns pause within the exact three-dimensional radius;
- existing NPCs remain alive and are not moved or despawned;
- scheduled marker spawning resumes after all overlapping suppressors are removed;
- deliberate Tamework spawns and manually triggered markers remain available.

Overlapping scarecrows require no special Tamework cache. Hytale tracks each suppressor by UUID, so removing one does not release a marker that is still covered by another.

### Collection

Looking at a scarecrow shows a localized hold-to-remove prompt. Holding the interaction for one second completes collection after normal entity-interaction reach validation. Collection verifies that the target carries the exact Tamework suppression asset ID before acting. The first release does not add a separate ownership or claim-provider bridge, so block-only claim integrations do not automatically gate placement or collection of the entity prop.

The entity is removed through the current ECS command boundary. Hytale then releases its UUID from the suppression controller and nearby spawn markers. One scarecrow item is returned to the player's inventory; if the inventory cannot accept it, the scarecrow remains in place. No separate scarecrow ownership system is introduced.

## Architecture

### Assets

- `Tamework_Scarecrow` item asset, icon, model, texture, bounds, and interaction references.
- `Tamework_Scarecrow` spawn-suppression asset under `Server/NPC/Spawn/Suppression`.
- Placement and collection interaction assets using stable Tamework interaction type IDs.
- Localization for the item name, description, and concise failure messages.

### Java

Keep registration in the existing 3,500-line `Tamework` plugin class thin. Feature logic belongs in focused collaborators.

- `TameworkPlaceScarecrowInteraction`: adapts Hytale interaction context to the placement service and applies success-only inventory mutation.
- `ScarecrowPlacementService`: validates the target and builds the persistent prop holder using Hytale's stock suppressor pattern.
- `TameworkCollectScarecrowInteraction`: validates the target suppressor and delegates collection.
- `ScarecrowCollectionService`: queues removal and returns or drops the item.

No ticking system, world-wide entity scan, spawn event interceptor, custom suppression resource, or change to `SpawnerFeatureHandler` is required. A custom scarecrow ECS component is unnecessary because the exact `SpawnSuppressionComponent` asset ID identifies the entity.

The persistent holder will contain the minimum native components needed for its behavior and presentation: transform, UUID, network/persistent model state, model/bounds, interaction state, prop/item identity where required by the selected Hytale prop pattern, and `SpawnSuppressionComponent`. Exact component construction will be verified against the 0.5.7 stock command and prop examples during implementation.

## Failure Handling

- Missing or invalid suppression assets abort placement before item consumption and log one actionable warning with the asset ID.
- Invalid, unloaded, obstructed, or stale targets abort without changing inventory.
- Collection rejects entities that do not carry the exact scarecrow suppression ID.
- Inventory-full collection removes the entity only when the returned item can be added or safely dropped as part of the same world-thread operation.
- All ECS writes use `CommandBuffer`; the feature does not introduce async player-component access or direct writes from runtime systems.
- Suppression persistence and overlap accounting remain owned by Hytale's native controller. Tamework does not attempt to repair or mirror that state.

## Validation

New tests must invoke production behavior and assert observable outcomes. No source-text, declaration-order, asset-presence, or raw-JSON inventory tests will be added.

Focused automated coverage, where the interaction boundary can be isolated without reproducing engine internals, will protect these regressions:

- a rejected placement does not consume the scarecrow item;
- a successful placement reports success and queues exactly one suppressor entity outcome;
- collection removes an eligible scarecrow and returns or drops exactly one item;
- collection refuses an unrelated suppressor entity.

Asset and integration validation will use the normal project workflow:

1. Validate the affected item, interaction, suppression, model, and localization assets against the exact 0.5.7 project profile.
2. Run `bash ../gradlew :alecstamework:test`.
3. Stage the mod through the shared workspace workflow.
4. Perform a bounded live-server check demonstrating world-spawn suppression, automatic marker pause/resume, unchanged existing NPCs, overlap behavior, placement failure without consumption, persistence across world reload, and collection cleanup.
5. Run the project ECS/thread-affinity guard checks if implementation touches a runtime system; the intended design does not require one.

## Documentation and Release Notes

Document the item, 32-block radius, automatic-only scope, overlap behavior, collection behavior, and the fact that existing/manual NPCs are unaffected. Add a player-facing `CHANGELOG.md` entry without changing the changelog version unless `gradle.properties` is also intentionally version-bumped.

## Rejected Alternatives

### Prefab-only placement

`SpawnPrefabInteraction` can paste entity-bearing prefabs, but using prefab serialization as the main placement contract makes item consumption, collision feedback, collection, and entity-version migration less explicit. It is not selected for the first implementation.

### Custom spawn interception

A custom system could scan for scarecrows and intercept, remove, or undo NPC spawns. This would duplicate Hytale's world-spawn and marker lifecycles, require continuous spatial work, and risk affecting deliberate Tamework spawns. It is unnecessary while the native suppression asset satisfies the desired behavior.

### True block plus hidden entity

A true block cannot directly carry `SpawnSuppressionComponent`, so it would require a synchronized hidden EntityStore entity for placement, break, chunk load, save, and repair. The selected visible placeable entity avoids that lifecycle bridge.
