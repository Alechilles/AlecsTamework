# Scarecrow Spawn Suppression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a placeable Tamework scarecrow that prevents ordinary world NPC spawns and automatic spawn-marker spawns within 32 blocks, while leaving manual marker triggers and deliberate Tamework spawns unchanged.

**Architecture:** Use Hytale's native `SpawnSuppressionComponent` and suppression asset instead of scanning NPCs or running a tick system. A focused placement service constructs the complete persistent entity holder before `AddReason.SPAWN`; two small interactions place and collect it. The shipped item inherits the vanilla `Deco_Scarecrow` visuals as a replaceable placeholder.

**Tech Stack:** Java 25, Hytale Server API 0.5.7, Gradle 9.5.1, JSON assets, JUnit 5.

## Global Constraints

- Work only on `feat/scarecrow-spawn-suppression` in `.worktrees/scarecrow-spawn-suppression`.
- Keep `Tamework.java` registration-only; put behavior under a new `items.scarecrow` package.
- Add no tick system, NPC scan, custom spawn hook, model, texture, or icon.
- Create the suppression component before adding the entity and remove the entity with `RemoveReason.REMOVE` so native marker suppression is released.
- Consume the exact active hotbar item only after placement validation succeeds.
- Respect Hytale's existing interaction/claim validation; do not add a parallel permission model.
- Add only behavior-level tests that catch a production regression. Do not add source-shape, JSON-text, asset-presence, or registration-presence tests.
- Keep deliberate Tamework spawns, manual marker triggers, existing NPCs, and unrelated spawn groups unchanged.

---

### Task 1: Centralize atomic active-hotbar consumption

**Files:**
- Modify: `src/test/java/com/alechilles/alecstamework/inventory/PlayerInventoryAccessTest.java`
- Modify: `src/main/java/com/alechilles/alecstamework/inventory/PlayerInventoryAccess.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/actions/InteractionItemConsumption.java`

- [x] Add a focused test proving a matching active stack loses exactly one item and a mismatched active stack remains unchanged. This protects against consuming a newly selected item if the player changes selection between validation and execution.
- [x] Run `bash ./gradlew test --tests com.alechilles.alecstamework.inventory.PlayerInventoryAccessTest --no-daemon` and confirm the new API test fails for the expected missing behavior.
- [x] Add `PlayerInventoryAccess.removeActiveHotbarItem(...)` overloads for `Player` and `Hotbar`, with an expected item ID and amount. Return `false` without mutation when the live slot is empty, mismatched, or insufficient.
- [x] Make `InteractionItemConsumption` delegate to the shared helper so existing NPC interactions retain the same exact-slot behavior.
- [x] Re-run the focused test and commit as `Refactor: centralize active hotbar consumption`.

---

### Task 2: Build the native suppressor entity before spawn

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/items/scarecrow/ScarecrowIds.java`
- Create: `src/main/java/com/alechilles/alecstamework/items/scarecrow/ScarecrowPlacementService.java`
- Create: `src/test/java/com/alechilles/alecstamework/items/scarecrow/ScarecrowPlacementServiceTest.java`

- [x] Add a placement-plan test proving the entity is centered immediately above the clicked block and faces the actor. This catches visibly offset or backward props.
- [x] Add a component-factory test proving the produced holder input carries the custom `BlockEntity`, one-item `ItemComponent`, UUID, prop/network prerequisites, collection interaction, and `SpawnSuppressionComponent` with the scarecrow suppression asset. Missing any of these changes observable placement, persistence, collection, or suppression behavior.
- [x] Run the focused test class and confirm it fails before production classes exist.
- [x] Implement constants for `Tamework_Scarecrow`, `Tamework_Scarecrow`, and `Root_Tamework_Scarecrow_Collect` in `ScarecrowIds`.
- [x] Implement a small placement service that validates the inherited block asset, a solid support block, the open placement cell, and the native suppression asset. Build the complete holder with transform, stock block rendering/hitbox components, persistent item identity, collection interaction, UUID, and suppression component before returning it.
- [x] Keep preparation results explicit (`SUCCESS`, invalid asset, invalid surface, occupied, or unavailable) so the interaction can provide useful player feedback without embedding validation logic.
- [x] Re-run the focused tests and compile; commit as `Feat: prepare scarecrow suppressor entities`.

---

### Task 3: Add placement and collection interactions

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/items/scarecrow/TameworkPlaceScarecrowInteraction.java`
- Create: `src/main/java/com/alechilles/alecstamework/items/scarecrow/TameworkCollectScarecrowInteraction.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`

- [x] Implement placement as a `SimpleBlockInteraction`, allowing the framework to enforce normal block-interaction and claim rules. Prepare the holder, atomically remove one live active `Tamework_Scarecrow`, then add it with `AddReason.SPAWN`.
- [x] If entity addition throws after consumption, return one scarecrow through `ItemUtils.interactivelyPickupItem` and report failure rather than silently losing the item.
- [x] Implement collection as a targeted instant interaction. Verify the target is this scarecrow, return one item only when inventory accepts it, and remove with `RemoveReason.REMOVE` to unregister native suppression.
- [x] Register only the two interaction codecs in `Tamework.java`.
- [x] Run focused inventory and placement-service tests plus `bash ./gradlew compileJava --no-daemon`. Do not substitute source-inspection tests for interaction behavior that requires the game runtime.
- [x] Commit as `Feat: add scarecrow placement and collection interactions`.

---

### Task 4: Ship stock-placeholder assets and player documentation

**Files:**
- Create: `src/main/resources/Server/Item/Items/Tamework/Tamework_Scarecrow.json`
- Create: `src/main/resources/Server/Item/Interactions/Tamework/Tamework_Scarecrow_Collect.json`
- Create: `src/main/resources/Server/Item/RootInteractions/Tamework/Root_Tamework_Scarecrow_Collect.json`
- Create: `src/main/resources/Server/NPC/Spawn/Suppression/Tamework_Scarecrow.json`
- Modify: `src/main/resources/Server/Languages/*/server.lang`
- Modify: `CHANGELOG.md`
- Create: `docs/Scarecrow.md`
- Create: `wiki/Player-Guides/Systems/Scarecrow-Spawn-Suppression.md`
- Modify: the nearest `docs/` and `wiki/` navigation indexes
- Mirror: `C:/Users/22ale/AppData/Roaming/Hytale/My Mod Docs/AlecsTamework.wiki/Player-Guides/Systems/Scarecrow-Spawn-Suppression.md`

- [ ] Define a suppression asset with radius `32`, an empty suppressed-groups list (all normal groups), and automatic marker suppression enabled.
- [ ] Define `Tamework_Scarecrow` with parent `Deco_Scarecrow`, inheriting the vanilla model, texture, icon, hitbox, and rotation as the temporary visual. Override only Tamework identity, text, and placement interaction.
- [ ] Define a `Use` root interaction for collecting the spawned prop and add concise placement-failure and item text keys to every shipped locale.
- [ ] Document the exact scope: ordinary world spawns and automatic marker spawns are blocked; manual marker triggers, deliberate mod spawns, and existing NPCs are not removed.
- [ ] Add a player-facing changelog entry and mirror the wiki page into the external wiki source.
- [ ] Validate changed assets with the Hytale asset tools exact-runtime profile. If the ignored local profile is absent, initialize it against the installed 0.5.7 runtime before running profile check, inspect/options, validate, and changed-asset checks.
- [ ] Run `pwsh -NoProfile -File ./scripts/tools/build-agent-index.ps1` and `pwsh -NoProfile -File ./scripts/tools/check-agent-docs.ps1` from Git Bash.
- [ ] Commit as `Feat: add scarecrow assets and documentation`.

---

### Task 5: Verify the complete branch

**Files:**
- Modify only files required by a concrete verification or review finding.

- [ ] Run the focused behavior tests.
- [ ] Run `bash ./gradlew test --no-daemon`.
- [ ] Run `bash ./gradlew assemble --no-daemon`.
- [ ] Run `git diff --check` and inspect `git status --short` for accidental or generated changes.
- [ ] Run the required ECS/thread-affinity scan: `rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java` and confirm this feature introduced no unsafe runtime access.
- [ ] Ask an independent reviewer to inspect native suppression lifecycle, item-loss recovery, target validation, persistence, and public asset compatibility. Apply and verify only concrete findings.
- [ ] Ask a test runner to independently repeat the final build/test checks after edit custody returns.
- [ ] Commit any review fixes separately, leaving the isolated worktree clean and ready for integration.
