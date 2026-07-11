package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiMapperTest {
    @Test
    void safeToJsonSkipsInternalAssetMetadata() {
        String json = ApiMapper.safeToJson(new Gson(), new BeanWithInternalData());

        assertTrue(json.contains("\"id\":\"example\""));
        assertFalse(json.contains("\"data\""));
        assertFalse(json.contains("serializationError"));
    }

    @Test
    void mapsProgressionSubviews() {
        CompanionHappinessService.HappinessSnapshot happinessSnapshot =
                new CompanionHappinessService.HappinessSnapshot(
                        62.5,
                        0.0,
                        100.0,
                        55.0,
                        60.0,
                        List.of(new CompanionHappinessModifierService.ModifierEntry("hunger_low", "Hunger: Low", -5.0))
                );
        var happiness = ApiMapper.mapHappiness("happy-config", 123L, "shared", happinessSnapshot);
        assertEquals("happy-config", happiness.configId());
        assertEquals(1, happiness.modifiers().size());
        assertEquals("hunger_low", happiness.modifiers().getFirst().id());

        TameworkNeedsComponent needsComponent = new TameworkNeedsComponent("needs-config", 12.5, 80.0, 7.0, 10L, 20L);
        var needs = ApiMapper.mapNeeds(needsComponent, null);
        assertEquals("needs-config", needs.configId());
        assertNull(needs.hungerPercent());
        assertEquals(7.0, needs.appliedHappinessPenalty());

        UUID partnerUuid = UUID.randomUUID();
        TameworkBreedingComponent breedingComponent =
                new TameworkBreedingComponent("breed-config", 70.0, 40L, true, true, 500L, partnerUuid, 100L, 400L);
        var breeding = ApiMapper.mapBreeding("breed-config", breedingComponent, 250L, 77.0, 65.0, true, 1.1);
        assertTrue(breeding.cooldownActive());
        assertEquals(250L, breeding.cooldownRemainingMs());
        assertEquals(1.1, breeding.fertilityMultiplier());

        TameworkLifeStageComponent lifeStageComponent =
                new TameworkLifeStageComponent("Baby", 1L, 2L, 3L, 4L, 0.4, 0.7, 0.75, 0.8, 0.9, 1.0, true);
        lifeStageComponent.setAdultRoleId("Cow");
        lifeStageComponent.setBabyRoleId("Cow_Calf");
        var lifeStage = ApiMapper.mapLifeStage("Baby", false, 0.55, lifeStageComponent);
        assertEquals("Baby", lifeStage.stage());
        assertEquals(0.55, lifeStage.currentScale());
        assertEquals("Cow", lifeStage.adultRoleId());

        TameworkTraitsComponent traitsComponent = new TameworkTraitsComponent(
                "traits-config",
                42L,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("SizeMultiplier", 1.15)
                }
        );
        var traits = ApiMapper.mapTraits(traitsComponent, null);
        assertEquals(42L, traits.rollSeed());
        assertEquals("SizeMultiplier", traits.values().getFirst().id());
        assertNull(traits.values().getFirst().effectKey());

        TameworkAttachmentsComponent attachmentsComponent =
                new TameworkAttachmentsComponent("attachment-config", Map.of("Horns", "Small"));
        var attachments = ApiMapper.mapAttachments(attachmentsComponent, Map.of("Horns", "Small", "Tail", "Long"));
        assertEquals("attachment-config", attachments.configId());
        assertEquals("Small", attachments.storedAttachmentIds().get("Horns"));
        assertEquals("Long", attachments.currentAttachmentIds().get("Tail"));
    }

    @Test
    void breedingViewPreservesSignedDeadlineAndSaturatesRemainingDuration() {
        TameworkBreedingComponent negativeDeadline = new TameworkBreedingComponent(
                "breed-config",
                70.0,
                1L,
                false,
                true,
                -100L,
                null,
                -250L,
                150L
        );
        var negativeView = ApiMapper.mapBreeding(
                "breed-config",
                negativeDeadline,
                -250L,
                70.0,
                65.0,
                true,
                1.0
        );
        TameworkBreedingComponent overflowingDeadline = new TameworkBreedingComponent(
                "breed-config",
                70.0,
                1L,
                false,
                true,
                Long.MAX_VALUE,
                null,
                Long.MIN_VALUE,
                Long.MAX_VALUE
        );
        var overflowView = ApiMapper.mapBreeding(
                "breed-config",
                overflowingDeadline,
                Long.MIN_VALUE,
                70.0,
                65.0,
                true,
                1.0
        );

        assertEquals(-100L, negativeView.cooldownUntilMs());
        assertEquals(150L, negativeView.cooldownRemainingMs());
        assertEquals(Long.MAX_VALUE, overflowView.cooldownRemainingMs());
    }

    private static final class BeanWithInternalData {
        @SuppressWarnings("unused")
        private final String id = "example";
        @SuppressWarnings("unused")
        private final InternalData data = new InternalData();
    }

    private static final class InternalData {
        @SuppressWarnings("unused")
        private final Class<?> declaredType = BeanWithInternalData.class;
    }
}
