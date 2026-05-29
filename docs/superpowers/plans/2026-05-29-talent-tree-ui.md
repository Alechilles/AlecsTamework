# Companion Talent Tree UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current paged talent list with a real branch/tier tree canvas that visually presents talent nodes, prerequisite links, node states, and focused purchase details.

**Architecture:** Keep `TwTalentConfig` authoring unchanged: `Branch`, `Tier`, `MinLevel`, `PointCost`, and `RequiresTalentIds` remain the source of truth. Add a small layout/view-model layer that converts talents into fixed UI slots and connector segments, then bind those slots into a static CustomUI asset. Use paging only for overflow branches or overflow tier bands, not as the primary experience.

**Tech Stack:** Java 25, Hytale CustomUI `.ui` assets, `InteractiveCustomUIPage`, `UICommandBuilder`, `UIEventBuilder`, JUnit source-contract and model tests.

---

## Design Decisions

- Use a static canvas because Hytale CustomUI pages are asset-defined, not DOM-like dynamic layouts.
- Support a primary page capacity of 5 branches by 6 tiers, for 30 visible node slots.
- Keep a deterministic overflow mode: if a config exceeds 5 branches or 6 tier rows, show branch/tier "pages" with Prev/Next controls.
- Show connectors as static thin `Group` elements. Java toggles their visibility and color based on whether the parent/child path is purchased, available, or locked.
- Use a focused detail panel instead of embedding long descriptions in each node. Nodes show short labels; selecting one updates detail text, status, cost, effects, and purchase button.
- Treat the current Animal Husbandry configs as the target dataset: 14 to 22 nodes per archetype should fit without pagination.
- Do not add coordinate fields to `TwTalentConfig` for v1.

## File Map

- Modify `src/main/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPage.java`
  - Owns CustomUI binding, selected node state, branch/tier page state, node click events, purchase/reset/back behavior.
- Create `src/main/java/com/alechilles/alecstamework/ui/TalentTreeLayoutService.java`
  - Converts unsorted talent entries into visible node slots and connector slots.
- Create `src/main/java/com/alechilles/alecstamework/ui/TalentTreeViewModel.java`
  - Immutable records for tree page, node slots, connector slots, selected node, overflow state.
- Modify `src/main/java/com/alechilles/alecstamework/items/CommandTalentPageService.java`
  - Builds richer `TreeNodeEntry` data: raw display name, branch, tier, prerequisites, effect summary, state reason, point cost.
- Replace `src/main/resources/Common/UI/Custom/TameworkCompanionTalentsPage.ui`
  - Static full-screen-ish tree canvas, node slots, connector segments, detail panel, controls.
- Modify `src/test/java/com/alechilles/alecstamework/ui/TameworkCompanionTalentsPageNavigationTest.java`
  - Update source-contract tests from list/page rows to tree slots, focus state, connector binding, overflow paging.
- Create `src/test/java/com/alechilles/alecstamework/ui/TalentTreeLayoutServiceTest.java`
  - Unit-test branch/tier placement, prerequisite connectors, overflow windows, collision handling.
- Modify `wiki/Modder-Documentation/Config-Reference/TwTalentConfig-Reference.md`
  - Document the 5 branch by 6 tier display guidance and overflow behavior.

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
        TalentTreeViewModel.TreePage page = new TalentTreeViewModel.TreePage(
                0,
                1,
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
                false,
                false
        );

        assertEquals(1, page.nodes().size());
        assertEquals(1, page.connectors().size());
        assertEquals("talent_a", page.selectedTalentId());
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

    public record TreePage(int branchPageIndex,
                           int branchPageCount,
                           @Nonnull List<String> visibleBranches,
                           @Nonnull List<NodeSlot> nodes,
                           @Nonnull List<ConnectorSlot> connectors,
                           @Nullable String selectedTalentId,
                           boolean hasPreviousPage,
                           boolean hasNextPage) {
        public TreePage {
            visibleBranches = List.copyOf(visibleBranches);
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

    TalentTreeViewModel.TreePage page = TalentTreeLayoutService.layout(entries, "care_a", 0);

    assertEquals(List.of("Breeding", "Care"), page.visibleBranches());
    assertEquals(3, page.nodes().size());
    assertEquals(0, slot(page, "breed_a").column());
    assertEquals(0, slot(page, "breed_a").row());
    assertEquals(1, slot(page, "care_a").column());
    assertEquals(0, slot(page, "care_a").row());
    assertEquals(1, slot(page, "care_b").column());
    assertEquals(1, slot(page, "care_b").row());
}

@Test
void layoutCreatesPrerequisiteConnectorsForVisibleNodes() {
    List<TameworkCompanionTalentsPage.TreeNodeEntry> entries = List.of(
            node("root", "Care", 1, "Purchased"),
            node("child", "Care", 2, "Available", "root")
    );

    TalentTreeViewModel.TreePage page = TalentTreeLayoutService.layout(entries, "child", 0);

    assertEquals(1, page.connectors().size());
    assertEquals("root", page.connectors().getFirst().fromTalentId());
    assertEquals("child", page.connectors().getFirst().toTalentId());
    assertEquals("Available", page.connectors().getFirst().state());
}

@Test
void layoutPaginatesWhenMoreThanFiveBranchesExist() {
    List<TameworkCompanionTalentsPage.TreeNodeEntry> entries = List.of(
            node("a", "A", 1, "Available"),
            node("b", "B", 1, "Available"),
            node("c", "C", 1, "Available"),
            node("d", "D", 1, "Available"),
            node("e", "E", 1, "Available"),
            node("f", "F", 1, "Available")
    );

    TalentTreeViewModel.TreePage first = TalentTreeLayoutService.layout(entries, "a", 0);
    TalentTreeViewModel.TreePage second = TalentTreeLayoutService.layout(entries, "f", 1);

    assertEquals(2, first.branchPageCount());
    assertEquals(List.of("A", "B", "C", "D", "E"), first.visibleBranches());
    assertEquals(List.of("F"), second.visibleBranches());
    assertEquals(true, first.hasNextPage());
    assertEquals(true, second.hasPreviousPage());
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

private static TalentTreeViewModel.NodeSlot slot(TalentTreeViewModel.TreePage page, String talentId) {
    return page.nodes().stream()
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
    static final int MAX_VISIBLE_BRANCHES = 5;
    static final int MAX_VISIBLE_TIERS = 6;

    private TalentTreeLayoutService() {
    }

    @Nonnull
    public static TalentTreeViewModel.TreePage layout(@Nonnull List<TameworkCompanionTalentsPage.TreeNodeEntry> entries,
                                                      @Nullable String selectedTalentId,
                                                      int requestedBranchPageIndex) {
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
        int pageCount = Math.max(1, (int) Math.ceil((double) branches.size() / (double) MAX_VISIBLE_BRANCHES));
        int pageIndex = Math.max(0, Math.min(requestedBranchPageIndex, pageCount - 1));
        int branchStart = pageIndex * MAX_VISIBLE_BRANCHES;
        int branchEnd = Math.min(branches.size(), branchStart + MAX_VISIBLE_BRANCHES);
        List<String> visibleBranches = branchStart < branchEnd ? branches.subList(branchStart, branchEnd) : List.of();

        Map<String, Integer> branchColumns = new HashMap<>();
        for (int i = 0; i < visibleBranches.size(); i++) {
            branchColumns.put(visibleBranches.get(i), i);
        }

        ArrayList<TalentTreeViewModel.NodeSlot> nodes = new ArrayList<>();
        Map<String, TalentTreeViewModel.NodeSlot> nodeByTalentId = new HashMap<>();
        int nodeSlot = 0;
        for (TameworkCompanionTalentsPage.TreeNodeEntry entry : sorted) {
            String branch = resolveBranch(entry.branchName());
            Integer column = branchColumns.get(branch);
            int row = Math.max(0, entry.tier() - 1);
            if (column == null || row >= MAX_VISIBLE_TIERS) {
                continue;
            }
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

        return new TalentTreeViewModel.TreePage(
                pageIndex,
                pageCount,
                visibleBranches,
                nodes,
                connectors,
                selected,
                pageIndex > 0,
                pageIndex + 1 < pageCount
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
    assertTrue(ui.contains("#TalentNode0"));
    assertTrue(ui.contains("#TalentNode29"));
    assertTrue(ui.contains("#TalentConnector0"));
    assertTrue(ui.contains("#TalentConnector39"));
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
  - Tree canvas: `Anchor: (Width: 820, Height: 560)`
  - Detail panel: `Anchor: (Width: 260, Height: 560)`
- Footer row: Back, Reset, Previous Branches, Next Branches, page indicator

Define 30 node groups:

```text
#TalentNode0..#TalentNode29
```

Each node group contains:

```text
#TalentNodeButtonN
#TalentNodeNameN
#TalentNodeCostN
#TalentNodeStateN
```

Define 40 connector groups:

```text
#TalentConnector0..#TalentConnector39
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
  - `TalentTreeViewModel.TreePage treePage = TalentTreeLayoutService.layout(data.entries(), selectedTalentId, branchPageIndex);`
  - Loop 0..29 and bind node visibility/text/state.
  - Loop 0..39 and bind connector visibility/state.
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

## Task 6: Validate Animal Husbandry Trees Against UI Capacity

**Files:**
- Create: `src/test/java/com/alechilles/alecstamework/ui/TalentTreeAnimalHusbandryCapacityTest.java`
- Optionally modify AH configs only if capacity fails.

- [ ] **Step 1: Add capacity test**

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

class TalentTreeAnimalHusbandryCapacityTest {
    @Test
    void animalHusbandryTalentConfigsFitPrimaryTreeCanvas() throws IOException {
        Path root = Path.of("..", "Alec's Animal Husbandry!", "Server", "Tamework", "Talents");
        assertFits(root.resolve("AHTalentLivestock.json"));
        assertFits(root.resolve("AHTalentNeutral.json"));
        assertFits(root.resolve("AHTalentCritter.json"));
        assertFits(root.resolve("AHTalentBeast.json"));
    }

    private static void assertFits(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(countUniqueBranches(json) <= TalentTreeLayoutService.MAX_VISIBLE_BRANCHES, path + " has too many branches");
        assertTrue(maxTier(json) <= TalentTreeLayoutService.MAX_VISIBLE_TIERS, path + " has too many tiers");
    }

    private static int countUniqueBranches(String json) {
        Matcher matcher = Pattern.compile("\"Branch\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        java.util.HashSet<String> branches = new java.util.HashSet<>();
        while (matcher.find()) {
            branches.add(matcher.group(1));
        }
        return branches.size();
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

- [ ] **Step 2: Run capacity test**

Run:

```powershell
.\mvnw.cmd "-Dtest=TalentTreeAnimalHusbandryCapacityTest" test
```

Expected: pass for current AH configs, except Beast may need tier compression if it uses tier 6.

- [ ] **Step 3: If Beast fails tier capacity, compress display tiers only**

If Beast has tier 6, do not weaken its talent prerequisites. Either:

- Increase `MAX_VISIBLE_TIERS` to 7 and update the UI to 35 slots, or
- Adjust Beast config tiers so final nodes fit tier 5 while preserving prerequisites.

Preferred: increase visible tiers to 7 if the UI height still fits at 720px.

- [ ] **Step 4: Commit**

```powershell
git add src/test/java/com/alechilles/alecstamework/ui/TalentTreeAnimalHusbandryCapacityTest.java src/main/java/com/alechilles/alecstamework/ui/TalentTreeLayoutService.java src/main/resources/Common/UI/Custom/TameworkCompanionTalentsPage.ui
git commit -m "Test: verify companion talent tree capacity"
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

The talent UI renders `Branch` values as columns and `Tier` values as rows.
For the best no-pagination presentation, keep a companion talent config to five or fewer branches and six or fewer tiers.

`RequiresTalentIds[]` draws prerequisite connector lines between visible nodes. Cross-branch prerequisites are allowed, but simple parent-to-child chains are easier to read in-game.

If a config exceeds the visible branch or tier capacity, the page uses tree paging. Paging should be reserved for unusually large configs; normal companion archetypes should fit on one tree page.
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
.\mvnw.cmd "-Dtest=TalentTreeLayoutServiceTest,TameworkCompanionTalentsPageNavigationTest,TalentTreeAnimalHusbandryCapacityTest" test
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
7. Repeat with beast config to verify larger trees fit without list-like pagination.

- [ ] **Step 5: Commit final cleanup**

```powershell
git status --short
git add src/main/java src/main/resources/Common/UI/Custom src/test/java wiki
git commit -m "Feat: replace talent list with tree UI"
```

---

## Self-Review

- Spec coverage: The plan replaces the list with a visual tree canvas, uses branch/tier/prereq authoring, preserves purchase/reset behavior, supports overflow pagination only as a fallback, and adds tests/docs/manual smoke.
- Placeholder scan: No implementation step relies on "TBD" or vague future work.
- Type consistency: `TreeNodeEntry`, `TalentTreeViewModel`, and `TalentTreeLayoutService.layout(...)` are defined before later tasks consume them.
