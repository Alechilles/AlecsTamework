package com.alechilles.alecstamework.npc.progression;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionAttachmentStateServiceTest {
    @Test
    void appliesResolvedSelectionsWhenPersistenceWasUpdatedEvenIfIdsAlreadyMatch() {
        Map<String, String> expected = Map.of("Coat", "Black", "Eyes", "BrightOrange");

        assertTrue(CompanionAttachmentStateService.shouldApplyResolvedSelections(
                true,
                expected,
                expected
        ));
    }

    @Test
    void skipsResolvedSelectionsWhenPersistenceAndCurrentIdsAlreadyMatch() {
        Map<String, String> expected = Map.of("Coat", "Black", "Eyes", "BrightOrange");

        assertFalse(CompanionAttachmentStateService.shouldApplyResolvedSelections(
                false,
                expected,
                expected
        ));
    }

    @Test
    void appliesResolvedSelectionsWhenCurrentIdsDifferFromStoredSelections() {
        assertTrue(CompanionAttachmentStateService.shouldApplyResolvedSelections(
                false,
                Map.of("Coat", "Black", "Eyes", "BrightOrange"),
                Map.of("Coat", "Black", "Eyes", "Hazel")
        ));
    }
}
