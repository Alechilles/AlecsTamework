package com.alechilles.alecstamework.integration.tooltips;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.herolias.tooltips.api.TooltipData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSpawnerTooltipProviderTest {

    @Test
    void additiveModeAppendsTooltipLines() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .spawnerTooltipMode(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE)
                .build());
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, new TranslationRegistry());

        TooltipData data = provider.getTooltipData(
                "*Spawner_Test_State_Filled",
                capturedMetadata("Fluffy", "Mob_Cat", "Fluffy").toJson(),
                "en-US"
        );

        assertNotNull(data);
        assertEquals("Fluffy (Mob_Cat)", data.getNameOverride());
        assertEquals(2, data.getLines().size());
        assertEquals("Name: Fluffy", data.getLines().get(0));
        assertTrue(data.getLines().get(1).startsWith("Role: "));
        assertNull(data.getDescriptionOverride());
    }

    @Test
    void replaceModeUsesDescriptionOverride() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .spawnerTooltipMode(ItemFeatureConfig.SpawnerTooltipMode.REPLACE)
                .build());
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, new TranslationRegistry());

        TooltipData data = provider.getTooltipData(
                "*Spawner_Test_State_Filled",
                capturedMetadata("Fluffy", "Mob_Cat", "Fluffy").toJson(),
                "en-US"
        );

        assertNotNull(data);
        assertEquals("Fluffy (Mob_Cat)", data.getNameOverride());
        assertEquals(0, data.getLines().size());
        assertNotNull(data.getDescriptionOverride());
        assertTrue(data.getDescriptionOverride().contains("Name: Fluffy"));
        assertTrue(data.getDescriptionOverride().contains("Role: "));
    }

    @Test
    void notCapturedMetadataReturnsNoTooltip() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .build());
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, new TranslationRegistry());

        BsonDocument metadata = new BsonDocument()
                .append(TameworkMetadataKeys.CAPTURED, BsonBoolean.FALSE)
                .append(TameworkMetadataKeys.CAPTURE_ROLE_ID, new BsonString("Mob_Cat"));

        TooltipData data = provider.getTooltipData("*Spawner_Test_State_Filled", metadata.toJson(), "en-US");

        assertNull(data);
    }

    @Test
    void languageFallbackStillBuildsTooltip() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .build());
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, new TranslationRegistry());

        TooltipData data = provider.getTooltipData(
                "*Spawner_Test_State_Filled",
                capturedMetadata(null, "Mob_Cat", "Buddy").toJson(),
                null
        );

        assertNotNull(data);
        assertEquals("Mob_Cat", data.getNameOverride());
        assertEquals("Name: Mob_Cat", data.getLines().get(0));
    }

    @Test
    void missingDisplayNameFallsBackToRoleName() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .build());
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, new TranslationRegistry());

        TooltipData data = provider.getTooltipData(
                "*Spawner_Test_State_Filled",
                capturedMetadata(null, "Mob_Cat", null).toJson(),
                "en-US"
        );

        assertNotNull(data);
        assertEquals("Mob_Cat", data.getNameOverride());
        assertEquals("Name: Mob_Cat", data.getLines().get(0));
    }

    @Test
    void emptyDisplayNameFallsBackToRoleName() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .build());
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, new TranslationRegistry());

        TooltipData data = provider.getTooltipData(
                "*Spawner_Test_State_Filled",
                capturedMetadata("", "Mob_Cat", null).toJson(),
                "en-US"
        );

        assertNotNull(data);
        assertEquals("Mob_Cat", data.getNameOverride());
        assertEquals("Name: Mob_Cat", data.getLines().get(0));
    }

    @Test
    void tooltipRoleNameUsesRoleForItemNameEvenWithGenericNpcName() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .build());
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, new TranslationRegistry());

        TooltipData data = provider.getTooltipData(
                "*Spawner_Test_State_Filled",
                capturedMetadata("Cat", "Cat", "Capture Crate").toJson(),
                "en-US"
        );

        assertNotNull(data);
        assertEquals("Cat", data.getNameOverride());
    }

    @Test
    void capturedEntityRoleFallbackAndGenericDisplayNameUseRoleOnly() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .build());
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, new TranslationRegistry());

        BsonDocument metadata = new BsonDocument()
                .append(TameworkMetadataKeys.CAPTURE_TOOLTIP_DISPLAY_NAME, new BsonString("Capture Crate"))
                .append("CapturedEntity", new BsonDocument().append("NpcNameKey", new BsonString("Cat")));

        TooltipData data = provider.getTooltipData(
                "*Spawner_Test_State_Filled",
                metadata.toJson(),
                "en-US"
        );

        assertNotNull(data);
        assertEquals("Cat", data.getNameOverride());
        assertEquals("Name: Cat", data.getLines().get(0));
    }

    private static BsonDocument capturedMetadata(String tooltipName, String roleId, String npcName) {
        BsonDocument metadata = new BsonDocument()
                .append(TameworkMetadataKeys.CAPTURED, BsonBoolean.TRUE);
        if (tooltipName != null) {
            metadata.append(TameworkMetadataKeys.CAPTURE_TOOLTIP_DISPLAY_NAME, new BsonString(tooltipName));
        }
        if (roleId != null) {
            metadata.append(TameworkMetadataKeys.CAPTURE_ROLE_ID, new BsonString(roleId));
        }
        if (npcName != null) {
            metadata.append(TameworkMetadataKeys.NPC_NAME, new BsonString(npcName));
        }
        return metadata;
    }
}
