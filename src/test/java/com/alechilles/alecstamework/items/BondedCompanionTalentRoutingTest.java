package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import java.lang.reflect.Field;
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
        assertTrue(bondedTalentService.contains("state.talents.getConfigId()"));
        assertTrue(bondedTalentService.contains("applyLiveProjection"));
        assertTrue(persistenceService.contains("snapshot.withTalents(updated)"));
        assertTrue(persistenceService.contains("request.talentConfigId()"));
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

    /** Regression: mirroring a saved allocation into a live NPC must retain its revision. */
    @Test
    void talentPageStateRetainsAllocationRevisionFromProfilePresentation()
            throws Exception {
        Class<?> stateType = Class.forName(
                BondedCompanionTalentPageService.class.getName() + "$State");
        Method from = stateType.getDeclaredMethod("from", java.util.UUID.class,
                com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation.class);
        from.setAccessible(true);
        var profile = BondedPanelTestFixtures.profile(
                "profile-1", 9L,
                com.alechilles.alecstamework.api.BondedCompanionStateView.STORED,
                java.util.UUID.fromString(
                        "71000000-0000-0000-0000-000000000009"),
                Map.of("level", "12", "levelingConfigId", "wyvern-leveling",
                        "talentConfigId", "fire-talents", "talentSpentPoints", "1",
                        "talentAllocationRevision", "7", "talents", "fire-root"));
        var row = BondedCompanionPanelFeaturePresentationSource.presentation(
                profile, 0L, null);

        Object state = from.invoke(null, BondedPanelTestFixtures.OWNER, row);
        Field talents = stateType.getDeclaredField("talents");
        talents.setAccessible(true);
        TameworkTalentsComponent component =
                (TameworkTalentsComponent) talents.get(state);

        assertEquals(7L, component.getAllocationRevision());
    }

    /** Regression: the saved response mirrored to an active NPC must keep its revision. */
    @Test
    void talentPageStateRetainsAllocationRevisionAfterSavedProfileUpdate()
            throws Exception {
        Class<?> stateType = Class.forName(
                BondedCompanionTalentPageService.class.getName() + "$State");
        Method from = stateType.getDeclaredMethod("from", java.util.UUID.class,
                com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation.class);
        from.setAccessible(true);
        var initial = BondedPanelTestFixtures.profile(
                "profile-1", 9L,
                com.alechilles.alecstamework.api.BondedCompanionStateView.STORED,
                java.util.UUID.fromString(
                        "71000000-0000-0000-0000-000000000009"),
                Map.of("level", "12", "levelingConfigId", "wyvern-leveling",
                        "talentConfigId", "fire-talents", "talentSpentPoints", "0",
                        "talentAllocationRevision", "7", "talents", ""));
        Object state = from.invoke(null, BondedPanelTestFixtures.OWNER,
                BondedCompanionPanelFeaturePresentationSource.presentation(
                        initial, 0L, null));
        var saved = BondedPanelTestFixtures.profile(
                "profile-1", 10L,
                com.alechilles.alecstamework.api.BondedCompanionStateView.ACTIVE,
                java.util.UUID.fromString(
                        "71000000-0000-0000-0000-000000000009"),
                Map.of("level", "12", "levelingConfigId", "wyvern-leveling",
                        "talentConfigId", "fire-talents", "talentSpentPoints", "1",
                        "talentAllocationRevision", "9", "talents", "fire-root"));
        Method apply = stateType.getDeclaredMethod("apply",
                com.alechilles.alecstamework.api.BondedCompanionProfileView.class);
        apply.setAccessible(true);

        apply.invoke(state, saved);
        Field talents = stateType.getDeclaredField("talents");
        talents.setAccessible(true);
        TameworkTalentsComponent component =
                (TameworkTalentsComponent) talents.get(state);

        assertEquals(9L, component.getAllocationRevision());
    }

    @Test
    void talentEffectsUsePlayerFacingDescriptionsInsteadOfRawEffectKeys()
            throws Exception {
        String service = Files.readString(Path.of("src", "main", "java",
                "com", "alechilles", "alecstamework", "items",
                "BondedCompanionTalentPageService.java"), StandardCharsets.UTF_8);

        assertTrue(service.contains("effectSummary(language, talent)"));
        assertTrue(service.contains("talent.getDescription()"));
        assertTrue(service.contains("formatEffectKey(language, effect.getEffectKey())"));
        assertFalse(service.contains("summaries.add(effect.getEffectKey())"));
    }

    @Test
    void reconciledTalentConfigOverridesTheBondedCompanionFamilyRole() throws Exception {
        String service = Files.readString(Path.of("src", "main", "java",
                "com", "alechilles", "alecstamework", "items",
                "BondedCompanionTalentPageService.java"), StandardCharsets.UTF_8);

        int roleLookup = service.indexOf("TwTalentConfig.resolveForRole(roleId)");
        int storedLookup = service.indexOf("TwTalentConfig.resolveById(talents.getConfigId())");
        assertTrue(roleLookup >= 0);
        assertTrue(storedLookup >= 0);
        assertTrue(storedLookup < roleLookup);
        assertTrue(service.contains("if (roleId == null || roleId.isBlank())"));
    }
}
