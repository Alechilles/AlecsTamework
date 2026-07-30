# Command Radial Menu Graphics Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render Tamework's fixed command wheel with the active cropped raster artwork from Alec's Radial Menu.

**Architecture:** Tamework keeps its existing page, events, and selectors. The UI stylesheet points each logical command slot at the matching cropped source slice and uses anchors derived from the standalone menu's full-size alpha bounds; one copied center-panel image completes the visual refresh.

**Tech Stack:** Hytale custom `.ui` assets, PNG resources, JUnit 5 resource-contract test, Maven.

## Global Constraints

- Copy only the active `RadialMenu/Default/Cropped` slice PNGs and `RadialMenu/Default/CommandWheelCenterPanel.png`.
- Do not copy `Vector/` assets, uncropped slice PNGs, or standalone renderer code.
- Preserve Tamework command selectors, event bindings, localized text, and command behavior.
- Use the exported artwork mapping `logical slot -> source slice`: `0->6, 1->7, 2->0, 3->1, 4->2, 5->3, 6->4, 7->5`.
- Use these fixed button anchors, in logical-slot order: `(100,461,224,207)`, `(195,573,207,224)`, `(421,573,207,224)`, `(533,461,224,207)`, `(533,235,224,207)`, `(421,140,207,224)`, `(195,140,207,224)`, `(100,235,224,207)`.
- Use these fixed label anchors, in logical-slot order: `(182,466,168,44)`, `(308,591,170,44)`, `(488,591,170,44)`, `(614,465,170,44)`, `(614,286,168,44)`, `(488,159,170,44)`, `(308,159,170,44)`, `(182,285,170,44)`.

---

### Task 1: Wire cropped assets into the fixed Tamework command wheel

**Files:**
- Create: `src/main/resources/Common/UI/Custom/Tamework/RadialMenu/Default/CommandWheelCenterPanel.png`
- Create: `src/main/resources/Common/UI/Custom/Tamework/RadialMenu/Default/Cropped/CommandWheelSlice{0..7}_{Default,Hover,Pressed}.png`
- Create: `src/test/java/com/alechilles/alecstamework/ui/TameworkCommandRadialMenuAssetsTest.java`
- Modify: `src/main/resources/Common/UI/Custom/TameworkCommandRadialMenu.ui:28-90,134-246`

**Interfaces:**
- Consumes: the active raster artwork at `C:/Users/22ale/AppData/Roaming/Hytale/Modding/alecs-radial-menu/src/main/resources/Common/UI/Custom/RadialMenu/Default`.
- Produces: `Tamework/RadialMenu/Default/*` resources and unchanged `#CommandButton0` through `#CommandButton7` selectors bound to their matching cropped assets.

- [ ] **Step 1: Write the failing resource contract test**

```java
@Test
void commandWheelUsesCroppedDefaultArtworkInStandaloneSlotOrder() throws Exception {
    String ui = Files.readString(UI, StandardCharsets.UTF_8);
    int[] sourceSliceBySlot = {6, 7, 0, 1, 2, 3, 4, 5};
    for (int slot = 0; slot < sourceSliceBySlot.length; slot++) {
        int sourceSlice = sourceSliceBySlot[slot];
        assertTrue(ui.contains("Tamework/RadialMenu/Default/Cropped/CommandWheelSlice"
                + sourceSlice + "_Default.png"));
        assertTrue(Files.isRegularFile(cropped(sourceSlice, "Default")));
        assertTrue(Files.isRegularFile(cropped(sourceSlice, "Hover")));
        assertTrue(Files.isRegularFile(cropped(sourceSlice, "Pressed")));
    }
    assertTrue(ui.contains("Tamework/RadialMenu/Default/CommandWheelCenterPanel.png"));
    assertTrue(Files.isRegularFile(CENTER_PANEL));
    assertFalse(ui.contains("Tamework/Vector/"));
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./mvnw -Dtest=TameworkCommandRadialMenuAssetsTest test`

Expected: FAIL because the Tamework `RadialMenu/Default` assets and UI references do not exist.

- [ ] **Step 3: Copy the approved raster resources and update the stylesheet**

```text
Source: RadialMenu/Default/Cropped/CommandWheelSlice6_{Default,Hover,Pressed}.png
Target: Tamework/RadialMenu/Default/Cropped/CommandWheelSlice6_{Default,Hover,Pressed}.png

Repeat for source slices 0 through 7 and all three states.
Source: RadialMenu/Default/CommandWheelCenterPanel.png
Target: Tamework/RadialMenu/Default/CommandWheelCenterPanel.png
```

Update each `@Slice{slot}ButtonStyle` to use its mapped source slice under
`Tamework/RadialMenu/Default/Cropped`. Replace the eight button and eight
label anchors with the fixed values in Global Constraints. Point
`#CommandWheelCenterPanel.Background` at the copied center-panel texture.

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `./mvnw -Dtest=TameworkCommandRadialMenuAssetsTest test`

Expected: PASS with the copied resources and every expected UI path present.

- [ ] **Step 5: Run the full regression suite and inspect the asset diff**

Run: `./mvnw test && git diff --check && git diff --stat`

Expected: Maven exits `0`, the diff has no whitespace errors, and only the radial UI, its test, and the 25 approved resource files are changed.

- [ ] **Step 6: Commit the implementation**

```bash
git add src/main/resources/Common/UI/Custom/TameworkCommandRadialMenu.ui \
  src/main/resources/Common/UI/Custom/Tamework/RadialMenu \
  src/test/java/com/alechilles/alecstamework/ui/TameworkCommandRadialMenuAssetsTest.java
git commit -m "Feat: refresh command radial menu graphics"
```
