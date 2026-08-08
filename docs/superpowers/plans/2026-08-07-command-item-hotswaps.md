# Command Item Hotswaps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each command flute assign and invoke three independent Q/E/R command hotswaps, including commands hidden from the eight-slot radial.

**Architecture:** Keep command execution in `CommandItemUseOrchestrator`. Add a small metadata store for the three command IDs, thin fixed-slot interaction handlers, and a selector/HUD projection; do not create a second command model or dispatcher. `ShowInRadial` extends `CommandEntry` with a default that preserves existing assets.

**Tech Stack:** Java, Hytale item interactions and ability slots, Hytale custom UI, `ItemStack` metadata, JUnit, Gradle.

## Global Constraints

- `ShowInRadial` defaults to `true`; omitted child config fields inherit through `TwCommandItemConfig`'s existing command-list replacement semantics.
- Hotswap assignments are per tool identity and may duplicate the same command ID.
- Q/E/R custom interaction requests identify a fixed slot only; server-side metadata decides the command ID.
- Existing primary linking, secondary menu opening, command targeting, recipients, cooldowns, and feedback remain unchanged.
- Tests must assert observable command/config/metadata behavior, not source text or UI-file presence.

---

### Task 1: Command visibility and assignment metadata

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwCommandItemConfig.java`
- Modify: `src/main/java/com/alechilles/alecstamework/config/TameworkMetadataKeys.java`
- Create: `src/main/java/com/alechilles/alecstamework/items/CommandHotswapAssignmentStore.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/CommandSelectionOptionSource.java`
- Test: `src/test/java/com/alechilles/alecstamework/config/assets/TwCommandItemConfigSelectionTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandHotswapAssignmentStoreTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/CommandSelectionOptionSourceTest.java`

**Interfaces:**
- Produces `CommandEntry#isShowInRadial()` and `CommandHotswapAssignmentStore.Slot { Q, E, R }`.
- Produces `read(ItemStack, Slot)`, `write(ItemStack, Slot, String)`, and `clear(ItemStack, Slot)`; null/blank command IDs represent unassigned.
- Produces a full command-option builder distinct from the eight-slot radial builder.

- [ ] **Step 1: Write failing behavior tests**

Add tests proving that a command entry omitting `ShowInRadial` remains radial-visible, an entry with `false` is excluded from a radial option list but present in an unbounded hotswap list, and independent item stacks retain different Q assignments.

```java
assertTrue(defaultEntry.isShowInRadial());
assertFalse(hiddenEntry.isShowInRadial());
assertEquals(List.of("Follow"), radialIds);
assertEquals(List.of("Follow", "Hold"), hotswapIds);
assertEquals("Follow", store.read(first, Slot.Q));
assertEquals("Hold", store.read(second, Slot.Q));
```

- [ ] **Step 2: Run the focused tests and verify they fail because the new field/store/full builder do not exist.**

Run: `bash ../gradlew :alecstamework:test --tests '*TwCommandItemConfigSelectionTest' --tests '*CommandHotswapAssignmentStoreTest' --tests '*CommandSelectionOptionSourceTest'`

- [ ] **Step 3: Implement the minimal config and metadata boundary.**

Add `ShowInRadial` to the command-entry codec and getter with a `true` default. Add three command metadata keys and make the store normalize blank values to unassigned. Retain `CommandSelectionOptionSource.build(...)` for the radial and add an unbounded method that takes the same predicate/localization path.

- [ ] **Step 4: Re-run the focused tests and verify they pass.**

- [ ] **Step 5: Commit the tested model change.**

### Task 2: Fixed-slot custom interactions and command dispatch

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/interactions/TameworkCommandHotswapInteraction.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandItemFeatureHandler.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandItemUseOrchestrator.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/CommandHotswapDispatchTest.java`

**Interfaces:**
- Consumes `CommandHotswapAssignmentStore` and a fixed `Slot` configured on the interaction asset.
- Produces `CommandItemFeatureHandler#handleHotswapUse(Player, ItemStack, Ref<EntityStore>, Slot)`.
- The feature handler reads only the held stack, resolves its stored assignment, then delegates to the existing `handleUse` path with that command ID.

- [ ] **Step 1: Write failing behavior tests**

Test that a Q hotswap delegates the Q assignment, that an unassigned slot does not execute a command, and that the handler never substitutes an assignment from another flute.

```java
assertEquals("Recall", dispatcher.dispatchedCommandId());
assertFalse(unassignedResult);
assertEquals(0, wrongFluteDispatcher.dispatchCount());
```

- [ ] **Step 2: Run the focused test and verify it fails because the hotswap handler does not exist.**

Run: `bash ../gradlew :alecstamework:test --tests '*CommandHotswapDispatchTest'`

- [ ] **Step 3: Implement the minimal fixed-slot interaction.**

Mirror the Flightmaster interaction lifecycle: server-authoritative `SimpleInteraction`, fail when the held item, player, handler, or assigned command is unavailable, and pass the existing interaction target to `handleHotswapUse`. Register one `TameworkCommandHotswap` interaction type with an inherited `Slot` enum field. Make `handleHotswapUse` resolve only the provided held stack and use its assignment as the existing command ID override.

- [ ] **Step 4: Re-run the focused test and verify it passes.**

- [ ] **Step 5: Commit the tested dispatch change.**

### Task 3: Radial selectors, persistent selection, and ability/hud assets

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandItemFeatureHandler.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/CommandSelectionPageEventBinder.java`
- Modify: `src/main/resources/Common/UI/Custom/TameworkCommandRadialMenu.ui`
- Create: `src/main/java/com/alechilles/alecstamework/ui/CommandHotswapHud.java`
- Create: `src/main/resources/Common/UI/Custom/TameworkCommandHotswapHud.ui`
- Modify: `src/main/resources/Server/Item/Items/Tools/Tamework_Command_Whistle_Example.json`
- Test: `src/test/java/com/alechilles/alecstamework/ui/CommandHotswapPresentationTest.java`

**Interfaces:**
- The menu page consumes one hotswap-dropdown entry list and current values plus a slot-assignment callback.
- The HUD consumes the currently held stack and active config and emits only assigned slot/key/icon/name view entries.
- The example flute binds `Ability1`, `Ability2`, and `Ability3` to `TameworkCommandHotswap` with `Slot: Q`, `E`, and `R` respectively.

- [ ] **Step 1: Write failing behavior tests**

Add a small presentation test proving that only assigned slots produce HUD entries, assigned commands use their configured icons, and a command absent from the radial remains available through the dropdown source.

```java
assertEquals(List.of(new Entry("Q", "Follow", "Icons/Follow.png")), entries);
assertFalse(entries.stream().anyMatch(entry -> entry.key().equals("E")));
```

- [ ] **Step 2: Run the focused test and verify it fails because the hotswap presentation does not exist.**

Run: `bash ../gradlew :alecstamework:test --tests '*CommandHotswapPresentationTest'`

- [ ] **Step 3: Implement the minimal menu and HUD presentation.**

Add Q/E/R dropdown rows right of the wheel. Bind each `ValueChanged` event to a distinct event-data key, persist through the feature handler/tool inventory authority, and refresh the row values without closing the page. Add a bottom-right custom HUD that projects assigned slots while the command flute is active; use a neutral texture when a command has no icon. Do not add dropdown icon rendering. Update the shipped example flute to expose the three custom ability interactions.

- [ ] **Step 4: Re-run the focused test and verify it passes.**

- [ ] **Step 5: Commit the tested presentation and example-asset change.**

### Task 4: Documentation and full verification

**Files:**
- Modify: `docs/Command-Items.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Document `ShowInRadial`, per-flute assignments, and the three ability interaction entries.**

- [ ] **Step 2: Add a concise player-facing changelog entry without bumping the release version.**

- [ ] **Step 3: Run the full test suite and asset staging check.**

Run: `bash ../gradlew :alecstamework:test && bash ../gradlew -p .. stageAllModAssets`

- [ ] **Step 4: Run thread-safety and whitespace checks.**

Run: `rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java && git diff --check`

- [ ] **Step 5: Commit the documentation and verification-ready feature.**
