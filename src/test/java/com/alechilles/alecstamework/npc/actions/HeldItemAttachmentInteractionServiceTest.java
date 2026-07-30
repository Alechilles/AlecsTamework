package com.alechilles.alecstamework.npc.actions;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure behavior coverage for held-item attachment planning and model gates. */
class HeldItemAttachmentInteractionServiceTest {

    @Test
    void postCommitRefreshFailureKeepsMutationSuccessfulAndReportsNpcContext() {
        ArrayList<String> warnings = new ArrayList<>();
        AtomicBoolean rollbackReached = new AtomicBoolean(false);

        boolean result = HeldItemAttachmentInteractionService.completeCommittedMutation(
                true,
                "npc-42",
                () -> { throw new IllegalStateException("transient rider unavailable"); },
                (message, error) -> warnings.add(message + ":" + error.getMessage()),
                () -> rollbackReached.set(true)
        );

        assertTrue(result);
        assertFalse(rollbackReached.get());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("NPC=npc-42"));
        assertTrue(warnings.getFirst().contains("periodic sweep"));
    }

    @Test
    void unsuccessfulMutationStillUsesTheCallerRollbackBoundary() {
        AtomicBoolean rollbackReached = new AtomicBoolean(false);

        boolean result = HeldItemAttachmentInteractionService.completeCommittedMutation(
                false, "npc-42", () -> { }, (message, error) -> { }, () -> rollbackReached.set(true));

        assertFalse(result);
        assertTrue(rollbackReached.get());
    }

    @Test
    void modelGateRequiresSlotAndOptionalSupportedValue() {
        Map<String, Set<String>> options = Map.of(
                "Saddle", Set.of("None", "Yes"),
                "SaddleBlanket", Set.of("None", "Blue")
        );

        assertTrue(HeldItemAttachmentInteractionService.supportsOptions(options, "SaddleBlanket", List.of()));
        assertTrue(HeldItemAttachmentInteractionService.supportsOptions(options, "Saddle", List.of("Yes")));
        assertFalse(HeldItemAttachmentInteractionService.supportsOptions(options, "Saddle", List.of("Red")));
        assertFalse(HeldItemAttachmentInteractionService.supportsOptions(options, "Missing", List.of()));
    }

    @Test
    void updatePreservesUnrelatedStoredSelections() {
        Map<String, String> updated = HeldItemAttachmentInteractionService.buildUpdatedSelections(
                Map.of("Coat", "Brown", "Saddle", "None"),
                Map.of("Coat", "Black", "Temporary", "Visible"),
                "Saddle",
                "Yes"
        );

        assertEquals(Map.of("Coat", "Brown", "Saddle", "Yes"), updated);
    }

    @Test
    void updateFallsBackToLiveStateAndRejectsAlreadyAppliedValue() {
        Map<String, String> updated = HeldItemAttachmentInteractionService.buildUpdatedSelections(
                Map.of(),
                Map.of("Coat", "Black"),
                "SaddleBlanket",
                "Blue"
        );

        assertEquals(Map.of("Coat", "Black", "SaddleBlanket", "Blue"), updated);
        assertNull(HeldItemAttachmentInteractionService.buildUpdatedSelections(
                Map.of("SaddleBlanket", "Blue"),
                Map.of(),
                "SaddleBlanket",
                "Blue"
        ));
    }

    @Test
    void exchangeCurrentValuePrefersStoredSlotAndOtherwiseUsesLiveSlot() {
        assertEquals("Blue", HeldItemAttachmentInteractionService.resolveCurrentSelection(
                Map.of("SaddleBlanket", "Blue"),
                Map.of("SaddleBlanket", "Canada"),
                "SaddleBlanket"
        ));
        assertEquals("Canada", HeldItemAttachmentInteractionService.resolveCurrentSelection(
                Map.of("Saddle", "Yes"),
                Map.of("SaddleBlanket", "Canada"),
                "SaddleBlanket"
        ));
    }

    @Test
    void exchangeUpdateMergesLiveAndStoredSelectionsBeforeChangingTarget() {
        assertEquals(
                Map.of(
                        "Coat", "Brown",
                        "Temporary", "Visible",
                        "Saddle", "None",
                        "SaddleBlanket", "Red"
                ),
                HeldItemAttachmentInteractionService.buildExchangeSelections(
                        Map.of("Coat", "Brown", "Saddle", "None"),
                        Map.of("Coat", "Black", "Temporary", "Visible", "SaddleBlanket", "Blue"),
                        "SaddleBlanket",
                        "Red"
                )
        );
    }
}
