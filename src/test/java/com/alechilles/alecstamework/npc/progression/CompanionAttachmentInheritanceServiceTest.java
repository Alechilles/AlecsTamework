package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.npc.progression.CompanionAttachmentInheritanceService.AttachmentInheritanceProfile;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests deterministic offspring attachment inheritance resolution. */
class CompanionAttachmentInheritanceServiceTest {

    @Test
    void returnsEmptyWhenInheritanceProfileDisabled() {
        Map<String, String> result = CompanionAttachmentInheritanceService.resolveInheritedSelections(
                Map.of("Fur", "0"),
                Map.of("Fur", "1"),
                Map.of("Fur", Set.of("0", "1")),
                42L,
                AttachmentInheritanceProfile.disabled()
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void prefersParentSelectionWhenRandomWeightAndMutationAreZero() {
        Map<String, String> result = CompanionAttachmentInheritanceService.resolveInheritedSelections(
                Map.of("Fur", "1"),
                Map.of(),
                Map.of("Fur", Set.of("0", "1")),
                73L,
                new AttachmentInheritanceProfile(true, 1.0, 0.0, 0.0)
        );

        assertEquals("1", result.get("Fur"));
    }

    @Test
    void ignoresInvalidParentValueAndFallsBackToDeterministicRandom() {
        Map<String, Set<String>> options = Map.of("Fur", Set.of("0", "1"));
        AttachmentInheritanceProfile profile = new AttachmentInheritanceProfile(true, 1.0, 0.0, 0.0);
        Map<String, String> first = CompanionAttachmentInheritanceService.resolveInheritedSelections(
                Map.of("Fur", "not-valid"),
                Map.of(),
                options,
                99L,
                profile
        );
        Map<String, String> second = CompanionAttachmentInheritanceService.resolveInheritedSelections(
                Map.of("Fur", "not-valid"),
                Map.of(),
                options,
                99L,
                profile
        );

        assertFalse(first.isEmpty());
        assertEquals(first, second);
        assertTrue(options.get("Fur").contains(first.get("Fur")));
    }

    @Test
    void mutationForcesRandomPath() {
        Map<String, Set<String>> options = Map.of("Fur", Set.of("0", "1", "2"));
        Map<String, String> result = CompanionAttachmentInheritanceService.resolveInheritedSelections(
                Map.of("Fur", "0"),
                Map.of("Fur", "0"),
                options,
                101L,
                new AttachmentInheritanceProfile(true, 1.0, 0.0, 1.0)
        );

        assertTrue(options.get("Fur").contains(result.get("Fur")));
    }
}
