package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

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
    void viewportWidthCanScaleFromExpandedCanvasContent() {
        int expandedContentWidth = TalentTreeLayoutService.resolveContentWidth(5);

        assertEquals(
                TalentTreeLayoutService.resolveViewportWidth(5),
                TalentTreeLayoutService.resolveViewportWidthForContent(expandedContentWidth)
        );
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

        assertTrue(fourColumnViewport < TalentTreeLayoutService.VIEWPORT_WIDTH);
        assertTrue(fiveColumnViewport > TalentTreeLayoutService.VIEWPORT_WIDTH);
        assertTrue(fourColumnRoot < fiveColumnRoot);
        assertEquals(
                TalentTreeLayoutService.resolveRootWidthForViewport(fiveColumnViewport),
                fiveColumnRoot
        );
    }

    private static TalentTreeViewModel.NodeSlot node(TalentTreeViewModel.TreeCanvas canvas, String id) {
        return canvas.nodes().stream()
                .filter(slot -> slot.entry().id().equals(id))
                .findFirst()
                .orElseThrow();
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
