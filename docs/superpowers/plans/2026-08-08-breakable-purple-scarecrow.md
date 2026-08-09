# Breakable Purple Scarecrow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make new Tamework scarecrows use native block placement and breaking, with a purple-hat texture and an invisible paired spawn suppressor.

**Architecture:** The child item inherits the vanilla scarecrow block/model contract but overrides its texture and stops invoking the custom placement interaction. Entity event systems defer place/break reconciliation until the world mutation completes, while a focused service owns exact-coordinate suppressor creation, deduplication, and removal.

**Tech Stack:** Java 25, Hytale 0.5.7 ECS and asset codecs, Gradle/JUnit 5, Hytale `.png`/item JSON assets.

## Global Constraints

- Work only in `.worktrees/scarecrow-suppression-fix` on `fix/scarecrow-suppression-registration`.
- Preserve the vanilla 128x128 texture dimensions, alpha, pixel shading, blue buttons, brown band, buckle, clothing, and wood; recolor only the blue hat UV pixels purple.
- `SpawnSuppressionComponent` stays on an invisible `EntityStore` entity; the real block remains in `ChunkStore`.
- Defer reconciliation and verify the resulting world block so cancelled/failed actions do not change suppression.
- Keep legacy collect interaction registration for already-placed entity scarecrows.
- Do not add source-shape or raw-JSON tests.

---

### Task 1: Purple-Hat Native Block Asset

**Files:**
- Create: `src/main/resources/Common/Blocks/Tamework/Scarecrow_Texture.png`
- Modify: `src/main/resources/Server/Item/Items/Tamework/Tamework_Scarecrow.json`

**Interfaces:**
- Consumes: Hytale 0.5.7 `Deco_Scarecrow` parent and `Blocks/Farming/Scarecrow.blockymodel` UV layout.
- Produces: native block id `Tamework_Scarecrow` using `Blocks/Tamework/Scarecrow_Texture.png`.

- [ ] **Step 1: Create the texture variant**

Copy the vanilla texture from `release-0.5.7-Assets.zip`, recolor only blue hat UV pixels on the right-side hat islands to a purple hue while preserving value/alpha, and save the 128x128 RGBA result at the mod-local path.

- [ ] **Step 2: Switch the child asset to native placement**

Set the child block texture override and remove the custom item and block interaction maps:

```json
"BlockType": {
  "CustomModelTexture": [
    {
      "Weight": 1,
      "Texture": "Blocks/Tamework/Scarecrow_Texture.png"
    }
  ]
}
```

- [ ] **Step 3: Validate the asset**

Run strict JSON parsing, inspect the PNG as 128x128 RGBA, and compare alpha plus non-hat pixels against the vanilla source.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/Common/Blocks/Tamework/Scarecrow_Texture.png src/main/resources/Server/Item/Items/Tamework/Tamework_Scarecrow.json
git commit -m "Feat: add purple breakable scarecrow asset"
```

### Task 2: Block-to-Suppressor Lifecycle Bridge

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/items/scarecrow/ScarecrowSuppressorService.java`
- Create: `src/main/java/com/alechilles/alecstamework/items/scarecrow/ScarecrowBlockEventSystems.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/scarecrow/ScarecrowSuppressorServiceTest.java`

**Interfaces:**
- Produces: `ScarecrowSuppressorService.reconcilePlaced(World, Vector3i)` and `reconcileBroken(World, Vector3i)`.
- Consumes: `ScarecrowIds.ITEM_ID`, `ScarecrowIds.SUPPRESSION_ID`, `PlaceBlockEvent`, and `BreakBlockEvent`.

- [ ] **Step 1: Write a focused failing production-behavior test**

Exercise the service's exact-position selection/removal against a real `Store<EntityStore>` fixture containing one matching scarecrow suppressor and one unrelated or differently positioned suppressor. Assert the matching ref is removed and the non-match remains.

- [ ] **Step 2: Run the focused test and confirm failure**

```bash
bash ../../gradlew test --tests '*ScarecrowSuppressorServiceTest'
```

Expected: compilation failure because `ScarecrowSuppressorService` does not exist.

- [ ] **Step 3: Implement the focused service**

Create holders containing only `TransformComponent`, `SpawnSuppressionComponent`, and `UUIDComponent`. Reconcile placement only when the resulting world block id is `Tamework_Scarecrow`; reconcile breaking only when it is no longer that id. Deduplicate and remove using exact suppression id plus a small coordinate epsilon.

- [ ] **Step 4: Add deferred ECS event systems**

```java
world.execute(() -> ScarecrowSuppressorService.reconcilePlaced(world, target));
world.execute(() -> ScarecrowSuppressorService.reconcileBroken(world, target));
```

The place system filters by held item id. The break system filters by `event.getBlockType().getId()`. Both copy `Vector3i` before deferring and use `Archetype.empty()` as their actor query.

- [ ] **Step 5: Register both systems**

Register `ScarecrowBlockEventSystems.Placed` and `.Broken` with the entity-store registry during Tamework startup. Keep custom collect registration for legacy entities.

- [ ] **Step 6: Run focused tests and commit**

```bash
bash ../../gradlew test --tests '*ScarecrowSuppressorServiceTest' --tests '*ScarecrowPlacementServiceTest' --tests '*TameworkCollectScarecrowInteractionTest'
git add src/main/java/com/alechilles/alecstamework/Tamework.java src/main/java/com/alechilles/alecstamework/items/scarecrow src/test/java/com/alechilles/alecstamework/items/scarecrow/ScarecrowSuppressorServiceTest.java
git commit -m "Feat: pair scarecrow blocks with suppressors"
```

### Task 3: Documentation and Full Verification

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/Scarecrow.md`
- Modify: `src/main/resources/Server/Languages/de-DE/server.lang`
- Modify: `src/main/resources/Server/Languages/en-US/server.lang`
- Modify: `src/main/resources/Server/Languages/es-ES/server.lang`
- Modify: `src/main/resources/Server/Languages/fr-CA/server.lang`
- Modify: `src/main/resources/Server/Languages/fr-FR/server.lang`
- Modify: `src/main/resources/Server/Languages/pt-BR/server.lang`

**Interfaces:**
- Consumes: completed asset and lifecycle behavior.
- Produces: player-facing documentation describing native breaking and the purple hat.

- [ ] **Step 1: Update final unreleased behavior wording**

Replace prompt/channel/entity-prop claims with native block placement, ordinary block-breaking removal, the paired invisible suppressor, and the purple hat. Update each localized item description to instruct normal block breaking. The old interaction-hint localization keys may remain for legacy placed entities. Do not document intermediate failed implementations.

- [ ] **Step 2: Validate Hytale references and safety constraints**

Run Hytale Workshop code-reference validation on the two new Java files, `git diff --check`, and:

```bash
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

- [ ] **Step 3: Run full verification**

```bash
bash ../../gradlew cleanTest test assemble
```

Inspect the packaged jar for the item JSON and purple texture. Report live preview, rendering, breaking, drop, suppression registration, and suppression removal as manual verification gaps unless tested in-game.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md docs/Scarecrow.md src/main/resources/Server/Languages
git commit -m "Docs: document breakable scarecrows"
```
