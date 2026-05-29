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

        assertEquals(TalentTreeLayoutService.VIEWPORT_WIDTH, canvas.width());
        assertEquals(TalentTreeLayoutService.VIEWPORT_HEIGHT, canvas.height());
        assertTrue(canvas.nodes().isEmpty());
        assertTrue(canvas.branches().isEmpty());
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
                "Health x1.02",
                TameworkCompanionTalentsPage.STATE_AVAILABLE.equals(state)
        );
    }
}
