package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TameworkCompanionTalentsPageNavigationTest {
    @Test
    void talentPanControlsCenterOverDynamicTreeViewport() {
        assertEquals(296, TameworkCompanionTalentsPage.resolvePanControlsLeft(704));
        assertEquals(354, TameworkCompanionTalentsPage.resolvePanControlsLeft(820));
        assertEquals(0, TameworkCompanionTalentsPage.resolvePanControlsLeft(80));
    }
}
