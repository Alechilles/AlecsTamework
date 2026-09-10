package com.alechilles.alecstamework.npc.progression;

import java.util.Map;
import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionAttachmentStateServiceTest {
    @Test
    void fillsLegacyEmptyAppearanceUsingModelWeightsAndKeepsChoicesOnReload() {
        ModelAsset model = appearanceModel();
        Map<String, String> repaired = CompanionAttachmentStateService.resolveLoadSelections(
                model, null, Map.of(), null);

        assertEquals(Map.of("Coat", "Black", "Eyes", "Hazel", "Equipment", "None"), repaired);
        assertEquals(repaired, CompanionAttachmentStateService.resolveLoadSelections(model, repaired, Map.of(), null));
    }

    @Test
    void preservesStoredAndCurrentChoicesEvenWhenTheirRandomWeightIsZero() {
        Map<String, String> repaired = CompanionAttachmentStateService.resolveLoadSelections(
                appearanceModel(), Map.of("Coat", "White"), Map.of("Coat", "Black", "Eyes", "Orange"), null);

        assertEquals(Map.of("Coat", "White", "Eyes", "Orange", "Equipment", "None"), repaired);
    }

    @Test
    void explicitMigrationWinsBeforeRandomBackfill() {
        TwAttachmentMigrationConfig migration = TwAttachmentMigrationConfig.CODEC.decode(BsonDocument.parse("""
                {"Rules":[{"SourceSlot":"Coat","TargetSlot":"Eyes",
                 "SourceToTarget":{"Black":"Orange"}}]}
                """), new ExtraInfo());

        Map<String, String> repaired = CompanionAttachmentStateService.resolveLoadSelections(
                appearanceModel(), Map.of("Coat", "Black"), Map.of(), migration);

        assertEquals(Map.of("Coat", "Black", "Eyes", "Orange", "Equipment", "None"), repaired);
    }

    private static ModelAsset appearanceModel() {
        // Zero-weight choices remain valid saved selections; None deliberately has no geometry.
        return ModelAsset.CODEC.decode(BsonDocument.parse("""
                {"Id":"AttachmentRepairTest",
                 "HitBox":{"Min":{"X":-0.5,"Y":0,"Z":-0.5},"Max":{"X":0.5,"Y":1,"Z":0.5}},
                 "RandomAttachmentSets":{
                  "Coat":{"Black":{"Weight":1},"White":{"Weight":0}},
                  "Eyes":{"Hazel":{"Weight":1},"Orange":{"Weight":0}},
                  "Equipment":{"None":{"Weight":1}}
                }}
                """), new ExtraInfo());
    }

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
