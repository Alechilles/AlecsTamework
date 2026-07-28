package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards the bonded roster's durable talent-page route. */
class BondedCompanionTalentRoutingTest {
    @Test
    void bondedRosterUsesProfileStateInsteadOfLegacyLinkRecords()
            throws Exception {
        String selectionService = Files.readString(Path.of("src", "main", "java",
                "com", "alechilles", "alecstamework", "items",
                "CommandSelectionPageService.java"), StandardCharsets.UTF_8);
        String bondedTalentService = Files.readString(Path.of("src", "main", "java",
                "com", "alechilles", "alecstamework", "items",
                "BondedCompanionTalentPageService.java"), StandardCharsets.UTF_8);
        String persistenceService = Files.readString(Path.of("src", "main", "java",
                "com", "alechilles", "alecstamework", "persistence", "bonded",
                "BondedCompanionTalentMutationService.java"), StandardCharsets.UTF_8);

        assertTrue(selectionService.contains("openBondedTalentPage(context, uuid)"));
        assertTrue(selectionService.contains("bondedTalentPages.open"));
        assertTrue(bondedTalentService.contains("BondedCompanionTalentActionRequest"));
        assertTrue(bondedTalentService.contains("applyLiveProjection"));
        assertTrue(persistenceService.contains("snapshot.withTalents(updated)"));
        assertTrue(persistenceService.contains("BondedCompanionOperation.Type.STORE"));
    }

    @Test
    void resetStateRetainsZeroSpentTalentPointsForTheImmediateTreeRefresh()
            throws Exception {
        Method integer = BondedCompanionTalentPageService.class.getDeclaredMethod(
                "integer", Map.class, String.class, int.class);
        integer.setAccessible(true);

        assertEquals(0, integer.invoke(null,
                Map.of("talentSpentPoints", "0"),
                "talentSpentPoints", 0));
    }
}
