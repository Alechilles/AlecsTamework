package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TalentTreeLayoutServiceTest {

    @Test
    void layoutGroupsNodesByBranchAndBuildsPrerequisiteConnectors() {
        List<TameworkCompanionTalentsPage.TreeNodeEntry> entries = List.of(
                entry("root", "Care", 1, TameworkCompanionTalentsPage.STATE_PURCHASED, List.of()),
                entry("child", "Care", 2, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("root")),
                entry("other", "Recovery", 1, TameworkCompanionTalentsPage.STATE_LOCKED, List.of())
        );

        TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(entries, "child");

        assertEquals("child", canvas.selectedTalentId());
        assertEquals(2, canvas.branches().size());
        assertEquals(3, canvas.nodes().size());
        assertEquals(1, canvas.connectors().size());
        assertTrue(canvas.nodes().stream().anyMatch(node -> node.entry().id().equals("child") && node.selected()));
        assertEquals(TameworkCompanionTalentsPage.STATE_AVAILABLE, canvas.connectors().get(0).state());
    }

    @Test
    void layoutDefaultsSelectionToFirstAvailableTalent() {
        List<TameworkCompanionTalentsPage.TreeNodeEntry> entries = List.of(
                entry("locked", "Care", 1, TameworkCompanionTalentsPage.STATE_LOCKED, List.of()),
                entry("available", "Care", 2, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of())
        );

        TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(entries, "missing");

        assertEquals("available", canvas.selectedTalentId());
    }

    @Test
    void layoutFansOutSiblingTalentsFromSharedPrerequisite() {
        List<TameworkCompanionTalentsPage.TreeNodeEntry> entries = List.of(
                entry("strong_blood", "Breeding", 3, TameworkCompanionTalentsPage.STATE_PURCHASED, List.of()),
                entry("mutagenic_line", "Breeding", 4, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("strong_blood")),
                entry("pack_line", "Breeding", 4, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("strong_blood"))
        );

        TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(entries, null);
        TalentTreeViewModel.NodeSlot strongBlood = node(canvas, "strong_blood");
        TalentTreeViewModel.NodeSlot mutagenicLine = node(canvas, "mutagenic_line");
        TalentTreeViewModel.NodeSlot packLine = node(canvas, "pack_line");

        assertEquals(1, canvas.branches().size());
        assertEquals(2, canvas.connectors().size());
        assertEquals(mutagenicLine.topY(), packLine.topY());
        assertTrue(mutagenicLine.centerX() < strongBlood.centerX());
        assertTrue(packLine.centerX() > strongBlood.centerX());
        assertEquals(
                (mutagenicLine.centerX() + packLine.centerX()) / 2,
                strongBlood.centerX(),
                2
        );
        assertTrue(canvas.width() > TalentTreeLayoutService.resolveContentWidth(1));
    }

    @Test
    void layoutSeparatesMiniwyvernCombatDependenciesThatShareATier() {
        List<TameworkCompanionTalentsPage.TreeNodeEntry> entries = List.of(
                entry("projectile", "Combat", 1, TameworkCompanionTalentsPage.STATE_PURCHASED, List.of()),
                entry("range", "Combat", 2, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("projectile")),
                entry("cadence", "Combat", 2, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("projectile")),
                entry("guidance", "Combat", 3, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("range")),
                entry("force", "Combat", 3, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("cadence")),
                entry("pattern", "Combat", 4, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("guidance")),
                entry("impact", "Combat", 4, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("force")),
                entry("assault", "Combat", 5, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("pattern", "impact")),
                entry("utility", "Combat", 5, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("assault")),
                entry("mastery", "Combat", 5, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("assault")),
                entry("apex", "Combat", 6, TameworkCompanionTalentsPage.STATE_AVAILABLE, List.of("utility", "mastery"))
        );

        TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(entries, null);

        assertTrue(node(canvas, "utility").topY() > node(canvas, "assault").topY());
        assertTrue(node(canvas, "mastery").topY() > node(canvas, "assault").topY());
        assertTrue(node(canvas, "apex").topY() > node(canvas, "mastery").topY());
        assertNoNodeOverlaps(canvas);
    }

    @Test
    void longBranchConnectorRunsAboveInterveningSiblingNodes() {
        List<TameworkCompanionTalentsPage.TreeNodeEntry> entries = List.of(
                entry("gentle_disposition", "Breeding", 1, TameworkCompanionTalentsPage.STATE_PURCHASED, List.of()),
                entry("patient_courtship", "Breeding", 2, TameworkCompanionTalentsPage.STATE_PURCHASED, List.of("gentle_disposition")),
                entry("strong_lineage", "Breeding", 3, TameworkCompanionTalentsPage.STATE_PURCHASED, List.of("patient_courtship")),
                entry("pattern_spark", "Breeding", 4, TameworkCompanionTalentsPage.STATE_LOCKED, List.of("strong_lineage")),
                entry("strange_spark", "Breeding", 4, TameworkCompanionTalentsPage.STATE_LOCKED, List.of("strong_lineage")),
                entry("stable_line", "Breeding", 5, TameworkCompanionTalentsPage.STATE_LOCKED, List.of("strong_lineage"))
        );

        TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(entries, null);
        TalentTreeViewModel.NodeSlot patternSpark = node(canvas, "pattern_spark");
        TalentTreeViewModel.NodeSlot strangeSpark = node(canvas, "strange_spark");
        TalentTreeViewModel.ConnectorSlot stableConnector = connectorTo(canvas, "stable_line");

        int horizontalTop = anchorValue(stableConnector.middleAnchor(), "top");
        int horizontalBottom = horizontalTop + anchorValue(stableConnector.middleAnchor(), "height");
        assertTrue(horizontalBottom <= patternSpark.topY(), "Stable line branch should run above Pattern Spark.");
        assertTrue(horizontalBottom <= strangeSpark.topY(), "Stable line branch should run above Strange Spark.");
    }

    @Test
    void viewportWidthCanScaleFromExpandedCanvasContent() {
        int expandedContentWidth = TalentTreeLayoutService.resolveContentWidth(5);

        assertEquals(
                TalentTreeLayoutService.resolveViewportWidth(5),
                TalentTreeLayoutService.resolveViewportWidthForContent(expandedContentWidth)
        );
    }

    @Test
    void talentNodeColumnsUseCompactBranchingTreeDimensions() {
        assertEquals(117, TalentTreeLayoutService.NODE_WIDTH);
        assertEquals(117, TalentTreeLayoutService.BRANCH_WIDTH);
        assertEquals(20, TalentTreeLayoutService.BRANCH_GAP);
    }

    @Test
    void layoutCapsNodeAndConnectorSlots() {
        ArrayList<TameworkCompanionTalentsPage.TreeNodeEntry> entries = new ArrayList<>();
        entries.add(entry("node0", "Care", 1, TameworkCompanionTalentsPage.STATE_PURCHASED, List.of()));
        for (int index = 1; index < 72; index++) {
            entries.add(entry(
                    "node" + index,
                    "Care",
                    index + 1,
                    TameworkCompanionTalentsPage.STATE_AVAILABLE,
                    List.of("node" + (index - 1))
            ));
        }

        TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(entries, null);

        assertEquals(TalentTreeViewModel.MAX_NODE_SLOTS, canvas.nodes().size());
        assertFalse(canvas.connectors().size() > TalentTreeViewModel.MAX_CONNECTOR_SLOTS);
    }

    @Test
    void emptyLayoutReturnsBlankCanvas() {
        TalentTreeViewModel.TreeCanvas canvas = TalentTreeLayoutService.layout(List.of(), null);

        assertEquals(TalentTreeLayoutService.resolveViewportWidth(0), canvas.width());
        assertEquals(TalentTreeLayoutService.VIEWPORT_HEIGHT, canvas.height());
        assertTrue(canvas.nodes().isEmpty());
        assertTrue(canvas.branches().isEmpty());
    }

    @Test
    void viewportAndRootWidthsScaleWithBranchCount() {
        int fourColumnViewport = TalentTreeLayoutService.resolveViewportWidth(4);
        int fiveColumnViewport = TalentTreeLayoutService.resolveViewportWidth(5);
        int fourColumnRoot = TalentTreeLayoutService.resolveRootWidth(4);
        int fiveColumnRoot = TalentTreeLayoutService.resolveRootWidth(5);

        assertTrue(fourColumnViewport <= TalentTreeLayoutService.VIEWPORT_WIDTH);
        assertTrue(fourColumnViewport < fiveColumnViewport);
        assertTrue(fourColumnRoot < fiveColumnRoot);
        assertEquals(
                TalentTreeLayoutService.resolveRootWidthForViewport(fiveColumnViewport),
                fiveColumnRoot
        );
    }

    @Test
    void viewportCanAutoScaleWideEnoughForTenCompactColumns() {
        int tenColumnContent = TalentTreeLayoutService.resolveContentWidth(10);
        int tenColumnViewport = TalentTreeLayoutService.resolveViewportWidth(10);
        int elevenColumnViewport = TalentTreeLayoutService.resolveViewportWidth(11);

        assertEquals(tenColumnContent + 16, tenColumnViewport);
        assertEquals(tenColumnViewport, TalentTreeLayoutService.MAX_VIEWPORT_WIDTH);
        assertEquals(TalentTreeLayoutService.MAX_VIEWPORT_WIDTH, elevenColumnViewport);
    }

    private static TalentTreeViewModel.NodeSlot node(TalentTreeViewModel.TreeCanvas canvas, String id) {
        return canvas.nodes().stream()
                .filter(slot -> slot.entry().id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static TalentTreeViewModel.ConnectorSlot connectorTo(TalentTreeViewModel.TreeCanvas canvas, String childId) {
        TalentTreeViewModel.NodeSlot child = node(canvas, childId);
        return canvas.connectors().stream()
                .filter(slot -> anchorValue(slot.endAnchor(), "left") <= child.centerX()
                        && anchorValue(slot.endAnchor(), "left") + anchorValue(slot.endAnchor(), "width") >= child.centerX()
                        && anchorValue(slot.endAnchor(), "top") + anchorValue(slot.endAnchor(), "height") == child.topY())
                .findFirst()
                .orElseThrow();
    }

    private static void assertNoNodeOverlaps(TalentTreeViewModel.TreeCanvas canvas) {
        for (int leftIndex = 0; leftIndex < canvas.nodes().size(); leftIndex++) {
            TalentTreeViewModel.NodeSlot left = canvas.nodes().get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < canvas.nodes().size(); rightIndex++) {
                TalentTreeViewModel.NodeSlot right = canvas.nodes().get(rightIndex);
                int leftX = left.centerX() - TalentTreeLayoutService.NODE_WIDTH / 2;
                int rightX = right.centerX() - TalentTreeLayoutService.NODE_WIDTH / 2;
                boolean separatedHorizontally = leftX + TalentTreeLayoutService.NODE_WIDTH <= rightX
                        || rightX + TalentTreeLayoutService.NODE_WIDTH <= leftX;
                boolean separatedVertically = left.bottomY() <= right.topY()
                        || right.bottomY() <= left.topY();
                assertTrue(separatedHorizontally || separatedVertically,
                        () -> left.entry().id() + " overlaps " + right.entry().id());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static int anchorValue(Anchor anchor, String fieldName) {
        try {
            Field field = Anchor.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Value<Integer> value = (Value<Integer>) field.get(anchor);
            return value == null ? 0 : value.getValue();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read anchor " + fieldName, exception);
        }
    }

    private static TameworkCompanionTalentsPage.TreeNodeEntry entry(String id,
                                                                    String branch,
                                                                    int tier,
                                                                    String state,
                                                                    List<String> requirements) {
        return new TameworkCompanionTalentsPage.TreeNodeEntry(
                id,
                branch,
                tier,
                state,
                id,
                "Description",
                state,
                1,
                1,
                requirements,
                requirements,
                "Health x1.02",
                TameworkCompanionTalentsPage.STATE_AVAILABLE.equals(state)
        );
    }
}
