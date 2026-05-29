# Companion Talent Tree UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current paged talent list with a real branch/tier tree canvas that visually presents talent nodes, prerequisite links, node states, and focused purchase details.

**Architecture:** Keep `TwTalentConfig` authoring unchanged: `Branch`, `Tier`, `MinLevel`, `PointCost`, and `RequiresTalentIds` remain the source of truth. Add a small layout/view-model layer that converts talents into fixed UI slots and connector segments, then bind those slots into a static CustomUI asset inside a native scrolling viewport. The tree never paginates; oversized trees extend the scrollable canvas.

**Tech Stack:** Java 25, Hytale CustomUI `.ui` assets, `InteractiveCustomUIPage`, `UICommandBuilder`, `UIEventBuilder`, JUnit source-contract and model tests.

---

## Design Decisions

- Use a static canvas because Hytale CustomUI pages are asset-defined, not DOM-like dynamic layouts.
- Support a primary viewport designed around 5 branches by 6 tiers, but build a larger scrollable canvas with spare node/connector slots so deeper trees can extend below the fold.
- Do not use branch/tier pagination. If a tree is larger than the visible area, the player scrolls the tree viewport and keeps the same spatial layout.
- Show connectors as static thin `Group` elements. Java toggles their visibility and color based on whether the parent/child path is purchased, available, or locked.
- Use a focused detail panel instead of embedding long descriptions in each node. Nodes show short labels; selecting one updates detail text, status, cost, effects, and purchase button.
- Treat the current Animal Husbandry configs as the target dataset: 14 to 22 nodes per archetype should fit comfortably, and deeper Beast chains should scroll instead of splitting into pages.
- Do not add coordinate fields to `TwTalentConfig` for v1.

## File Map

- Modify `src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java`
  - Owns CustomUI binding, selected node state, scrollable tree node click events, purchase/reset/back behavior.
- Create `src/main/java/com/alechilles/alecstamework/ui/TalentTreeLayoutService.java`
  - Converts unsorted talent entries into visible node slots and connector slots.
- Create `src/main/java/com/alechilles/alecstamework/ui/TalentTreeViewModel.java`
  - Immutable records for tree canvas, node slots, connector slots, selected node, and computed content size.
- Modify `src/main/java/com/alechilles/alecstamework/items/CommandTalentPageService.java`
  - Builds richer `TreeNodeEntry` data: raw display name, branch, tier, prerequisites, effect summary, state reason, point cost.
- Replace `src/main/resources/Common/UI/Custom/TameworkCompanionTalentsPage.ui`
  - Static full-screen-ish tree canvas, node slots, connector segments, detail panel, controls.
- Modify `src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java`
  - Update source-contract tests from list/page rows to tree slots, focus state, connector binding, and scroll viewport structure.
- Create `src/test/java/com/alechilles/alecstamework/ui/TalentTreeLayoutServiceTest.java`
  - Unit-test branch/tier placement, prerequisite connectors, scroll content sizing, and collision handling.
- Modify `wiki/Modder-Documentation/Config-Reference/TwTalentConfig-Reference.md`
  - Document branch/tier display guidance and scroll behavior.

---

## Task 1: Add Tree Layout Model

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/ui/TalentTreeViewModel.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/TalentTreeLayoutServiceTest.java`

- [ ] **Step 1: Write failing model shape test**

Create `TalentTreeLayoutServiceTest.java` with a first test that references the model records:

```java
package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TalentTreeLayoutServiceTest {
    @Test
    void treePageCarriesNodesConnectorsAndOverflowState() {
        TalentTreeViewModel.TreeCanvas canvas = new TalentTreeViewModel.TreeCanvas(
                820,
                760,
                List.of("Care"),
                List.of(new TalentTreeViewModel.NodeSlot(
                        0,
                        "talent_a",
                        "Care",
                        1,
                        0,
                        0,
                        "Purchased",
                        true
                )),
                List.of(new TalentTreeViewModel.ConnectorSlot(
                        0,
                        "talent_a",
                        "talent_b",
                        0,
                        0,
                        0,
                        1,
                        "Locked",
                        true
                )),
                "talent_a",
                false
        );

        assertEquals(1, canvas.nodes().size());
        assertEquals(1, canvas.connectors().size());
        assertEquals("talent_a", canvas.selectedTalentId());
        assertEquals(760, canvas.contentHeight());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\mvnw.cmd "-Dtest=TalentTreeLayoutServiceTest" test
```

Expected: fails because `TalentTreeViewModel` does not exist.

- [ ] **Step 3: Add immutable view-model records**

Create `TalentTreeViewModel.java`:

```java
package com.alechilles.alecstamework.ui;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class TalentTreeViewModel {
    private TalentTreeViewModel() {
    }

    public record TreeCanvas(int contentWidth,
                             int contentHeight,
                             @Nonnull List<String> branches,
                             @Nonnull List<NodeSlot> nodes,
                             @Nonnull List<ConnectorSlot> connectors,
                             @Nullable String selectedTalentId,
                             boolean scrollable) {
        public TreeCanvas {
            branches = List.copyOf(branches);
            nodes = List.copyOf(nodes);
            connectors = List.copyOf(connectors);
        }
    }

    public record NodeSlot(int slotIndex,
                           @Nonnull String talentId,
                           @Nonnull String branchName,
                           int tier,
                           int column,
                           int row,
                           @Nonnull String state,
                           boolean visible) {
    }

    public record ConnectorSlot(int slotIndex,
                                @Nonnull String fromTalentId,
                                @Nonnull String toTalentId,
                                int fromColumn,
                                int fromRow,
                                int toColumn,
                                int toRow,
                                @Nonnull String state,
                                boolean visible) {
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```powershell
.\mvnw.cmd "-Dtest=TalentTreeLayoutServiceTest" test
```

Expected: pass or advance to unrelated build environment errors only.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/ui/TalentTreeViewModel.java src/test/java/com/alechilles/alecstamework/ui/TalentTreeLayoutServiceTest.java
git commit -m "Feat: add talent tree view model"
```

---

## Task 2: Implement Branch/Tier Layout

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/ui/TalentTreeLayoutService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/TalentTreeLayoutServiceTest.java`

- [ ] **Step 1: Add failing layout tests**

Append these tests:

```java
@Test
void layoutGroupsBranchesIntoColumnsAndTiersIntoRows() {
    List<TameworkCompanionTalentsPage.TreeNodeEntry> entries = List.of(
            node("care_a", "Care", 1, "Available"),
            node("care_b", "Care", 2, "Locked"),
            node("breed_a", "Breeding", 1, "Purchased")
    );

    TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(entries, "care_a");

    assertEquals(List.of("Breeding", "Care"), canvas.branches());
    assertEquals(3, canvas.nodes().size());
    assertEquals(0, slot(canvas, "breed_a").column());
    assertEquals(0, slot(canvas, "breed_a").row());
    assertEquals(1, slot(canvas, "care_a").column());
    assertEquals(0, slot(canvas, "care_a").row());
    assertEquals(1, slot(canvas, "care_b").column());
    assertEquals(1, slot(canvas, "care_b").row());
}

@Test
void layoutCreatesPrerequisiteConnectorsForVisibleNodes() {
    List<TameworkCompanionTalentsPage.TreeNodeEntry> entries = List.of(
            node("root", "Care", 1, "Purchased"),
            node("child", "Care", 2, "Available", "root")
    );

    TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(entries, "child");

    assertEquals(1, canvas.connectors().size());
    assertEquals("root", canvas.connectors().getFirst().fromTalentId());
    assertEquals("child", canvas.connectors().getFirst().toTalentId());
    assertEquals("Available", canvas.connectors().getFirst().state());
}

@Test
void layoutExpandsCanvasForLargeTrees() {
    List<TameworkCompanionTalentsPage.TreeNodeEntry> entries = List.of(
            node("a", "A", 1, "Available"),
            node("b", "B", 1, "Available"),
            node("c", "C", 1, "Available"),
            node("d", "D", 1, "Available"),
            node("e", "E", 1, "Available"),
            node("f", "F", 1, "Available")
    );

    TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(entries, "a");

    assertEquals(List.of("A", "B", "C", "D", "E", "F"), canvas.branches());
    assertEquals(6, canvas.nodes().size());
    assertEquals(true, canvas.contentWidth() > TalentTreeLayoutService.VIEWPORT_WIDTH);
    assertEquals(true, canvas.scrollable());
}

private static TameworkCompanionTalentsPage.TreeNodeEntry node(String id,
                                                              String branch,
                                                              int tier,
                                                              String state,
                                                              String... prerequisites) {
    return new TameworkCompanionTalentsPage.TreeNodeEntry(
            id,
            branch,
            tier,
            state,
            id,
            "Description",
            state + " - Cost 1 point",
            1,
            1,
            List.of(prerequisites),
            "Effect summary",
            TameworkCompanionTalentsPage.STATE_AVAILABLE.equals(state)
    );
}

private static TalentTreeViewModel.NodeSlot slot(TalentTreeViewModel.TreeCanvas canvas, String talentId) {
    return canvas.nodes().stream()
            .filter(node -> talentId.equals(node.talentId()))
            .findFirst()
            .orElseThrow();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\mvnw.cmd "-Dtest=TalentTreeLayoutServiceTest" test
```

Expected: fails because `TalentTreeLayoutService.layout` and the richer `TreeNodeEntry` record do not exist.

- [ ] **Step 3: Expand `TreeNodeEntry`**

Change `TreeNodeEntry` in `TameworkCompanionTalentsPage.java` to:

```java
public record TreeNodeEntry(@Nonnull String id,
                            @Nonnull String branchName,
                            int tier,
                            @Nonnull String state,
                            @Nonnull String displayName,
                            @Nonnull String description,
                            @Nonnull String status,
                            int pointCost,
                            int minLevel,
                            @Nonnull List<String> requiredTalentIds,
                            @Nonnull String effectSummary,
                            boolean canPurchase) {
    public TreeNodeEntry {
        requiredTalentIds = List.copyOf(requiredTalentIds);
    }
}
```

- [ ] **Step 4: Implement layout service**

Create `TalentTreeLayoutService.java`:

```java
package com.alechilles.alecstamework.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class TalentTreeLayoutService {
    static final int VIEWPORT_WIDTH = 820;
    static final int VIEWPORT_HEIGHT = 560;
    static final int MAX_NODE_SLOTS = 60;
    static final int MAX_CONNECTOR_SLOTS = 80;
    private static final int COLUMN_WIDTH = 150;
    private static final int ROW_HEIGHT = 92;
    private static final int HORIZONTAL_PADDING = 36;
    private static final int VERTICAL_PADDING = 28;

    private TalentTreeLayoutService() {
    }

    @Nonnull
    public static TalentTreeViewModel.TreeCanvas layout(@Nonnull List<TameworkCompanionTalentsPage.TreeNodeEntry> entries,
                                                        @Nullable String selectedTalentId) {
        List<TameworkCompanionTalentsPage.TreeNodeEntry> sorted = entries.stream()
                .filter(entry -> entry != null && entry.id() != null && !entry.id().isBlank())
                .sorted(Comparator
                        .comparing((TameworkCompanionTalentsPage.TreeNodeEntry entry) -> normalize(entry.branchName()))
                        .thenComparingInt(TameworkCompanionTalentsPage.TreeNodeEntry::tier)
                        .thenComparing(entry -> normalize(entry.displayName())))
                .toList();

        LinkedHashSet<String> branchSet = new LinkedHashSet<>();
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : sorted) {
            branchSet.add(resolveBranch(entry.branchName()));
        }
        List<String> branches = List.copyOf(branchSet);

        Map<String, Integer> branchColumns = new HashMap<>();
        for (int i = 0; i < branches.size(); i++) {
            branchColumns.put(branches.get(i), i);
        }

        ArrayList<TalentTreeViewModel.NodeSlot> nodes = new ArrayList<>();
        Map<String, TalentTreeViewModel.NodeSlot> nodeByTalentId = new HashMap<>();
        int nodeSlot = 0;
        int maxRow = 0;
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : sorted) {
            String branch = resolveBranch(entry.branchName());
            Integer column = branchColumns.get(branch);
            int row = Math.max(0, entry.tier() - 1);
            if (column == null) {
                continue;
            }
            maxRow = Math.max(maxRow, row);
            TalentTreeViewModel.NodeSlot slot = new TalentTreeViewModel.NodeSlot(
                    nodeSlot++,
                    entry.id(),
                    branch,
                    entry.tier(),
                    column,
                    row,
                    entry.state(),
                    true
            );
            nodes.add(slot);
            nodeByTalentId.put(entry.id(), slot);
        }

        ArrayList<TalentTreeViewModel.ConnectorSlot> connectors = new ArrayList<>();
        int connectorSlot = 0;
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : sorted) {
            TalentTreeViewModel.NodeSlot child = nodeByTalentId.get(entry.id());
            if (child == null) {
                continue;
            }
            for (String requiredId : entry.requiredTalentIds()) {
                TalentTreeViewModel.NodeSlot parent = nodeByTalentId.get(requiredId);
                if (parent == null) {
                    continue;
                }
                connectors.add(new TalentTreeViewModel.ConnectorSlot(
                        connectorSlot++,
                        parent.talentId(),
                        child.talentId(),
                        parent.column(),
                        parent.row(),
                        child.column(),
                        child.row(),
                        entry.state(),
                        true
                ));
            }
        }

        String selected = selectedTalentId;
        if (selected == null || !nodeByTalentId.containsKey(selected)) {
            selected = nodes.isEmpty() ? null : nodes.getFirst().talentId();
        }

        int contentWidth = Math.max(VIEWPORT_WIDTH, HORIZONTAL_PADDING * 2 + branches.size() * COLUMN_WIDTH);
        int contentHeight = Math.max(VIEWPORT_HEIGHT, VERTICAL_PADDING * 2 + (maxRow + 1) * ROW_HEIGHT);

        return new TalentTreeViewModel.TreeCanvas(
                contentWidth,
                contentHeight,
                branches,
                nodes,
                connectors,
                selected,
                contentWidth > VIEWPORT_WIDTH || contentHeight > VIEWPORT_HEIGHT
        );
    }

    @Nonnull
    private static String resolveBranch(@Nullable String branchName) {
        return branchName == null || branchName.isBlank() ? "General" : branchName.trim();
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run:

```powershell
.\mvnw.cmd "-Dtest=TalentTreeLayoutServiceTest" test
```

Expected: pass or advance to unrelated environment issues.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/ui/TalentTreeLayoutService.java src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java src/test/java/com/alechilles/alecstamework/ui/TalentTreeLayoutServiceTest.java
git commit -m "Feat: lay out companion talent tree nodes"
```

---

## Task 3: Build Rich Talent Page Data

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/CommandTalentPageService.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java`

- [ ] **Step 1: Add failing source-contract test**

Add a test asserting that page data includes costs, prerequisites, and effect summaries:

```java
@Test
void pageDataCarriesPurchaseDetailsForFocusedTreeNodes() throws IOException {
    String content = Files.readString(Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework", "items", "CommandTalentPageService.java"
    ), StandardCharsets.UTF_8);

    assertTrue(content.contains("talent.getPointCost()"));
    assertTrue(content.contains("talent.getMinLevel()"));
    assertTrue(content.contains("List.of(talent.getRequiresTalentIds())"));
    assertTrue(content.contains("formatTalentEffectSummary(talent)"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\mvnw.cmd "-Dtest=TameworkCompanionTalentsPageNavigationTest" test
```

Expected: fails until the richer data is wired.

- [ ] **Step 3: Populate richer `TreeNodeEntry` fields**

In `CommandTalentPageService`, change the `new TreeNodeEntry(...)` call to include:

```java
talent.getPointCost(),
talent.getMinLevel(),
List.of(talent.getRequiresTalentIds()),
formatTalentEffectSummary(talent),
canPurchase
```

Add helper:

```java
@Nonnull
private String formatTalentEffectSummary(@Nonnull TwTalentConfig.TalentDefinition talent) {
    ArrayList<String> parts = new ArrayList<>();
    for (TwTalentConfig.TalentEffect effect : talent.getEffects()) {
        if (effect == null || effect.getEffectKey() == null || effect.getEffectKey().isBlank()) {
            continue;
        }
        double multiplier = effect.getMultiplier();
        String prefix = multiplier >= 1.0 ? "x" : "x";
        parts.add(effect.getEffectKey() + " " + prefix + String.format(Locale.ROOT, "%.2f", multiplier));
    }
    return parts.isEmpty() ? "No passive effects" : String.join(", ", parts);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```powershell
.\mvnw.cmd "-Dtest=TameworkCompanionTalentsPageNavigationTest" test
```

Expected: pass or advance to unrelated environment issues.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/items/CommandTalentPageService.java src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java
git commit -m "Feat: expose talent tree detail data"
```

---

## Task 4: Replace List UI With Static Tree Canvas

**Files:**
- Replace: `src/main/resources/Common/UI/Custom/TameworkCompanionTalentsPage.ui`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java`

- [ ] **Step 1: Add failing UI structure test**

Add:

```java
@Test
void talentPageUiDefinesTreeCanvasNodeSlotsAndConnectors() throws IOException {
    String ui = Files.readString(Path.of(
            "src", "main", "resources", "Common", "UI", "Custom", "TameworkCompanionTalentsPage.ui"
    ), StandardCharsets.UTF_8);

    assertTrue(ui.contains("#TalentTreeCanvas"));
    assertTrue(ui.contains("#TalentTreeViewport"));
    assertTrue(ui.contains("LayoutMode: TopScrolling"));
    assertTrue(ui.contains("KeepScrollPosition: true"));
    assertTrue(ui.contains("ScrollbarStyle: $C.@DefaultScrollbarStyle"));
    assertTrue(ui.contains("#TalentNode0"));
    assertTrue(ui.contains("#TalentNode59"));
    assertTrue(ui.contains("#TalentConnector0"));
    assertTrue(ui.contains("#TalentConnector79"));
    assertTrue(ui.contains("#TalentDetailPanel"));
    assertTrue(ui.contains("#TalentDetailPurchaseButton"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\mvnw.cmd "-Dtest=TameworkCompanionTalentsPageNavigationTest" test
```

Expected: fails because current UI only has list rows.

- [ ] **Step 3: Replace page layout**

Replace the row list with:

- Root container: `Anchor: (Width: 1120, Height: 720)`
- Header row: title, level summary, points summary
- Main row:
  - Tree viewport: `Group #TalentTreeViewport` with `Anchor: (Width: 820, Height: 560)`, `LayoutMode: TopScrolling`, `KeepScrollPosition: true`, and `ScrollbarStyle: $C.@DefaultScrollbarStyle`
  - Tree canvas/content inside the viewport: `Group #TalentTreeCanvas` with an anchor tall enough for deep trees, for example `Anchor: (Width: 980, Height: 980)`
  - Detail panel: `Anchor: (Width: 260, Height: 560)`
- Footer row: Back and Reset. Do not include previous/next branch controls or a page indicator.

Define 60 node groups:

```text
#TalentNode0..#TalentNode59
```

Each node group contains:

```text
#TalentNodeButtonN
#TalentNodeNameN
#TalentNodeCostN
#TalentNodeStateN
```

Define 80 connector groups:

```text
#TalentConnector0..#TalentConnector79
```

Each connector is a `Group` with small height or width. For v1, use orthogonal line segments by exposing multiple connector segment slots if the UI parser handles them better:

```text
#TalentConnectorHorizontalN
#TalentConnectorVerticalN
```

Use state colors:

- Purchased: `#65e084`
- Available: `#f0c75e`
- Unaffordable: `#8794a6`
- Locked: `#465062`
- Selected border: `#ffffff`

- [ ] **Step 4: Bind tree slots from Java**

In `TameworkCompanionTalentsPage.bindPage`:

- Replace row loop with:
  - `TalentTreeViewModel.TreeCanvas treeCanvas = TalentTreeLayoutService.layout(data.entries(), selectedTalentId);`
  - Bind `#TalentTreeCanvas.Anchor` or fixed canvas size selectors if CustomUI supports runtime anchor updates; otherwise size the `.ui` canvas for the expected maximum and let unused space scroll.
  - Loop 0..59 and bind node visibility/text/state.
  - Loop 0..79 and bind connector visibility/state.
  - Bind selected node details.

Add event actions:

```java
private static final String ACTION_SELECT_PREFIX = "Select:";
```

Handle selection:

```java
if (data.action.startsWith(ACTION_SELECT_PREFIX)) {
    selectedTalentId = data.action.substring(ACTION_SELECT_PREFIX.length());
    sendRefreshUpdate();
    return;
}
```

- [ ] **Step 5: Run UI structure test**

Run:

```powershell
.\mvnw.cmd "-Dtest=TameworkCompanionTalentsPageNavigationTest" test
```

Expected: pass or advance to unrelated environment issues.

- [ ] **Step 6: Commit**

```powershell
git add src/main/resources/Common/UI/Custom/TameworkCompanionTalentsPage.ui src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java
git commit -m "Feat: render companion talents as a tree"
```

---

## Task 5: Improve Node State Styling and Purchase Flow

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java`

- [ ] **Step 1: Add failing source-contract test**

Add:

```java
@Test
void talentTreeBindsNodeStateColorsSelectionAndPurchaseRefresh() throws IOException {
    String content = Files.readString(Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework", "ui", "TameworkCompanionTalentsPage.java"
    ), StandardCharsets.UTF_8);

    assertTrue(content.contains("resolveNodeColor"));
    assertTrue(content.contains("resolveConnectorColor"));
    assertTrue(content.contains("selectedTalentId"));
    assertTrue(content.contains("ACTION_SELECT_PREFIX"));
    assertTrue(content.contains("#TalentDetailPurchaseButton.Visible"));
    assertTrue(content.contains("statusMessage = purchaseCallback.apply(selectedTalentId)"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\mvnw.cmd "-Dtest=TameworkCompanionTalentsPageNavigationTest" test
```

Expected: fails until state styling and selected purchase are implemented.

- [ ] **Step 3: Implement state color helpers**

Add:

```java
@Nonnull
private String resolveNodeColor(@Nonnull String state, boolean selected) {
    if (selected) {
        return "#ffffff";
    }
    if (STATE_PURCHASED.equals(state)) {
        return "#65e084";
    }
    if (STATE_AVAILABLE.equals(state)) {
        return "#f0c75e";
    }
    if (STATE_UNAFFORDABLE.equals(state)) {
        return "#8794a6";
    }
    return "#465062";
}

@Nonnull
private String resolveConnectorColor(@Nonnull String state) {
    if (STATE_PURCHASED.equals(state)) {
        return "#65e084";
    }
    if (STATE_AVAILABLE.equals(state)) {
        return "#f0c75e";
    }
    return "#465062";
}
```

- [ ] **Step 4: Purchase selected node from detail panel**

Bind detail purchase button to:

```java
EventData.of(KEY_ACTION, ACTION_BUY_SELECTED)
```

Handle:

```java
if (ACTION_BUY_SELECTED.equalsIgnoreCase(data.action) && selectedTalentId != null && purchaseCallback != null) {
    statusMessage = purchaseCallback.apply(selectedTalentId);
    sendRefreshUpdate();
    return;
}
```

- [ ] **Step 5: Run test to verify it passes**

Run:

```powershell
.\mvnw.cmd "-Dtest=TameworkCompanionTalentsPageNavigationTest" test
```

Expected: pass or advance to unrelated environment issues.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java
git commit -m "Feat: add talent tree selection states"
```

---

## Task 6: Validate Animal Husbandry Trees Against Scroll Canvas

**Files:**
- Create: `src/test/java/com/alechilles/alecstamework/ui/TalentTreeAnimalHusbandryScrollCanvasTest.java`
- Optionally increase static node/connector slot counts if a real config exceeds them.

- [ ] **Step 1: Add scroll-canvas test**

Create:

```java
package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TalentTreeAnimalHusbandryScrollCanvasTest {
    @Test
    void animalHusbandryTalentConfigsFitScrollCanvasSlots() throws IOException {
        Path root = Path.of("..", "Alec's Animal Husbandry!", "Server", "Tamework", "Talents");
        assertFitsSlots(root.resolve("AHTalentLivestock.json"));
        assertFitsSlots(root.resolve("AHTalentNeutral.json"));
        assertFitsSlots(root.resolve("AHTalentCritter.json"));
        assertFitsSlots(root.resolve("AHTalentBeast.json"));
    }

    private static void assertFitsSlots(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(countTalents(json) <= TalentTreeLayoutService.MAX_NODE_SLOTS, path + " has too many talents for static node slots");
        assertTrue(countPrerequisites(json) <= TalentTreeLayoutService.MAX_CONNECTOR_SLOTS, path + " has too many prerequisite connectors");
        assertTrue(maxTier(json) >= 1, path + " should have at least one tier");
    }

    private static int countTalents(String json) {
        Matcher matcher = Pattern.compile("\"Id\"\\s*:\\s*\"AH_").matcher(json);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countPrerequisites(String json) {
        Matcher matcher = Pattern.compile("\"AH_[^\"]+\"").matcher(json);
        int count = 0;
        boolean inRequires = false;
        for (String line : json.split("\\R")) {
            if (line.contains("\"RequiresTalentIds\"")) {
                inRequires = true;
            }
            if (inRequires) {
                Matcher lineMatcher = matcher.matcher(line);
                while (lineMatcher.find()) {
                    count++;
                }
                if (line.contains("]")) {
                    inRequires = false;
                }
            }
        }
        return count;
    }

    private static int maxTier(String json) {
        Matcher matcher = Pattern.compile("\"Tier\"\\s*:\\s*(\\d+)").matcher(json);
        int max = 0;
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return max;
    }
}
```

- [ ] **Step 2: Run scroll-canvas test**

Run:

```powershell
.\mvnw.cmd "-Dtest=TalentTreeAnimalHusbandryScrollCanvasTest" test
```

Expected: pass for current AH configs. Tier count and branch count must not fail the test because scrolling is the overflow mechanism.

- [ ] **Step 3: If a config exceeds static slots, increase slots**

If a real config exceeds the static slot count, increase the static `.ui` capacity and constants. Do not compress branches or tiers just to make the tree fit.

- Increase node slots in batches of 20.
- Increase connector slots in batches of 40.
- Keep the viewport size stable and let the content canvas grow.

- [ ] **Step 4: Commit**

```powershell
git add src/test/java/com/alechilles/alecstamework/ui/TalentTreeAnimalHusbandryScrollCanvasTest.java src/main/java/com/alechilles/alecstamework/ui/TalentTreeLayoutService.java src/main/resources/Common/UI/Custom/TameworkCompanionTalentsPage.ui
git commit -m "Test: verify companion talent tree scroll canvas"
```

---

## Task 7: Update Docs and Manual Smoke Checklist

**Files:**
- Modify: `wiki/Modder-Documentation/Config-Reference/TwTalentConfig-Reference.md`
- Modify: `wiki/Modder-Documentation/System-Integration/Progression-Systems-Guide.md`

- [ ] **Step 1: Document authoring guidance**

Add a section to `TwTalentConfig-Reference.md`:

```markdown
## Talent Tree UI Guidance

The talent UI renders `Branch` values as columns and `Tier` values as rows inside a scrollable tree viewport.
Companion configs do not need to fit a fixed visible grid; larger trees should extend the canvas and remain scrollable.

`RequiresTalentIds[]` draws prerequisite connector lines between visible nodes. Cross-branch prerequisites are allowed, but simple parent-to-child chains are easier to read in-game.

If a config is unusually large, increase the static node/connector slot capacity in the UI asset rather than adding page navigation. Do not split a single tree into pages.
```

- [ ] **Step 2: Document player interaction**

Add to `Progression-Systems-Guide.md`:

```markdown
The companion talent page presents talents as a branch/tier tree. Select a node to inspect its cost, level requirement, prerequisites, and passive effects, then unlock it from the detail panel when it is available.
```

- [ ] **Step 3: Run docs grep**

Run:

```powershell
rg -n "Talent Tree UI Guidance|branch/tier tree|detail panel" wiki
```

Expected: new docs lines are found.

- [ ] **Step 4: Commit**

```powershell
git add wiki/Modder-Documentation/Config-Reference/TwTalentConfig-Reference.md wiki/Modder-Documentation/System-Integration/Progression-Systems-Guide.md
git commit -m "Docs: document talent tree UI authoring"
```

---

## Task 8: Final Verification

**Files:**
- No new code expected.

- [ ] **Step 1: Run focused tests**

Run:

```powershell
.\mvnw.cmd "-Dtest=TalentTreeLayoutServiceTest,TameworkCompanionTalentsPageNavigationTest,TalentTreeAnimalHusbandryScrollCanvasTest" test
```

Expected: all focused tests pass.

- [ ] **Step 2: Run Tamework package build**

If the live Hytale jars are locked, close Hytale or point Maven at local copies of the system jars.

Run:

```powershell
.\mvnw.cmd test
```

Expected: pass. If unrelated stale tests fail, run:

```powershell
.\mvnw.cmd "-Dmaven.test.skip=true" package
```

Expected: production package succeeds; record the stale-test failures separately.

- [ ] **Step 3: Run thread-safety grep**

Run:

```powershell
rg "PlayerRef\.getComponent\(Player|getComponent\(Player\.getComponentType\(\)\)|Universe\.get\(\).*getPlayers" -n src/main/java
```

Expected: no new unsafe runtime player access introduced.

- [ ] **Step 4: Manual UI smoke**

In game:

1. Open one livestock companion talent page.
2. Verify branches render as columns and tiers render vertically.
3. Select locked, available, unaffordable, and purchased nodes.
4. Verify the detail panel updates without leaving the tree.
5. Purchase a prerequisite node and verify connectors/node colors update.
6. Reset talents and verify the tree returns to unpurchased state.
7. Repeat with beast config to verify larger trees scroll without list-like pagination.

- [ ] **Step 5: Commit final cleanup**

```powershell
git status --short
git add src/main/java src/main/resources/Common/UI/Custom src/test/java wiki
git commit -m "Feat: replace talent list with tree UI"
```

---

## Self-Review

- Spec coverage: The plan replaces the list with a visual tree canvas, uses branch/tier/prereq authoring, preserves purchase/reset behavior, uses scrolling for oversized trees, and adds tests/docs/manual smoke.
- Placeholder scan: No implementation step relies on "TBD" or vague future work.
- Type consistency: `TreeNodeEntry`, `TalentTreeViewModel`, and `TalentTreeLayoutService.layout(...)` are defined before later tasks consume them.
