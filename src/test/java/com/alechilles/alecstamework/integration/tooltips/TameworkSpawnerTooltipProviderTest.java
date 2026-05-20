package com.alechilles.alecstamework.integration.tooltips;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwAttachmentDisplayConfig;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.attachments.AttachmentDisplayResolver;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
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
        assertTrue(data.getLines().get(1).startsWith("Species: "));
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
        assertTrue(data.getDescriptionOverride().contains("Species: "));
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

    @Test
    void roleNameKeyMetadataResolvesTamedRoleTooltipDisplay() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .build());
        TranslationRegistry translations = new TranslationRegistry();
        translations.put("npcRoles.Bison.name", "Bison");
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, translations);

        BsonDocument metadata = capturedMetadata("Tamed_Bison", "Tamed_Bison", null)
                .append(TameworkMetadataKeys.CAPTURE_NAME_KEY, new BsonString("server.npcRoles.Bison.name"));

        TooltipData data = provider.getTooltipData(
                "*Spawner_Test_State_Filled",
                metadata.toJson(),
                "en-US"
        );

        assertNotNull(data);
        assertEquals("Bison", data.getNameOverride());
        assertEquals("Name: Bison", data.getLines().get(0));
        assertEquals("Species: Bison", data.getLines().get(1));
    }

    @Test
    void roleNameKeyCandidateHandlesServerPluralAgainstUnprefixedLanguageEntry() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .build());
        TranslationRegistry translations = new TranslationRegistry();
        translations.put("npcRoles.Bison.name", "Bison");
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, translations);

        BsonDocument metadata = capturedMetadata(null, "Tamed_Bison", null)
                .append(TameworkMetadataKeys.CAPTURE_NAME_KEY, new BsonString("server.npcRoles.Bison.name"));

        TooltipData data = provider.getTooltipData(
                "*Spawner_Test_State_Filled",
                metadata.toJson(),
                "en-US"
        );

        assertNotNull(data);
        assertEquals("Bison", data.getNameOverride());
    }

    @Test
    void legacyTamedRoleIdResolvesBaseSpeciesTranslationWithoutNameKeyMetadata() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .build());
        TranslationRegistry translations = new TranslationRegistry();
        translations.put("npcRoles.Armadillo.name", "Armadillo");
        TameworkSpawnerTooltipProvider provider = new TameworkSpawnerTooltipProvider(registry, translations);

        BsonDocument metadata = capturedMetadata("Tamed_Armadillo", "Tamed_Armadillo", null);

        TooltipData data = provider.getTooltipData(
                "*Spawner_Test_State_Filled",
                metadata.toJson(),
                "en-US"
        );

        assertNotNull(data);
        assertEquals("Armadillo", data.getNameOverride());
        assertEquals("Name: Armadillo", data.getLines().get(0));
        assertEquals("Species: Armadillo", data.getLines().get(1));
    }

    @Test
    void additiveModeAppendsResolvedAttachmentLines() throws Exception {
        ItemFeatureRegistry registry = registry(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE);
        TameworkSpawnerTooltipProvider provider = provider(registry, displayConfig(
                "Display_Model",
                0,
                true,
                entry("model", appliesTo(null, new String[] { "Model_Cat" }, null, null),
                        set("BaseColor", "Coat", Map.of("Black", "Black Coat")))
        ));

        BsonDocument metadata = capturedMetadata("Fluffy", "Mob_Cat", "Fluffy")
                .append(TameworkMetadataKeys.CAPTURE_MODEL_ID, new BsonString("Model_Cat"))
                .append(TameworkMetadataKeys.ATTACHMENTS, new BsonString("{\"BaseColor\":\"Black\"}"));

        TooltipData data = provider.getTooltipData("*Spawner_Test_State_Filled", metadata.toJson(), "en-US");

        assertNotNull(data);
        assertEquals(3, data.getLines().size());
        assertEquals("Coat: Black Coat", data.getLines().get(2));
    }

    @Test
    void replaceModeIncludesResolvedAttachmentLinesInDescription() throws Exception {
        ItemFeatureRegistry registry = registry(ItemFeatureConfig.SpawnerTooltipMode.REPLACE);
        TameworkSpawnerTooltipProvider provider = provider(registry, displayConfig(
                "Display_Role",
                0,
                true,
                entry("role", appliesTo(new String[] { "Mob_Cat" }, null, null, null),
                        set("BaseColor", "Coat", Map.of("Black", "Black Coat")))
        ));

        BsonDocument metadata = capturedMetadata("Fluffy", "Mob_Cat", "Fluffy")
                .append(TameworkMetadataKeys.ATTACHMENTS, new BsonString("{\"BaseColor\":\"Black\"}"));

        TooltipData data = provider.getTooltipData("*Spawner_Test_State_Filled", metadata.toJson(), "en-US");

        assertNotNull(data);
        assertEquals(0, data.getLines().size());
        assertNotNull(data.getDescriptionOverride());
        assertTrue(data.getDescriptionOverride().contains("Coat: Black Coat"));
    }

    @Test
    void malformedAttachmentMetadataDoesNotBreakTooltip() throws Exception {
        ItemFeatureRegistry registry = registry(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE);
        TameworkSpawnerTooltipProvider provider = provider(registry, displayConfig(
                "Display_Global",
                0,
                true,
                entry("global", appliesTo(null, null, null, null),
                        set("BaseColor", "Coat", Map.of("Black", "Black Coat")))
        ));

        BsonDocument metadata = capturedMetadata("Fluffy", "Mob_Cat", "Fluffy")
                .append(TameworkMetadataKeys.ATTACHMENTS, new BsonString("not-json"));

        TooltipData data = provider.getTooltipData("*Spawner_Test_State_Filled", metadata.toJson(), "en-US");

        assertNotNull(data);
        assertEquals(2, data.getLines().size());
    }

    @Test
    void oldMetadataWithoutModelIdResolvesByRoleAndGlobalFallback() throws Exception {
        ItemFeatureRegistry registry = registry(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE);
        TameworkSpawnerTooltipProvider provider = provider(registry, displayConfig(
                "Display_Role",
                0,
                true,
                entry("role", appliesTo(new String[] { "Mob_Cat" }, null, null, null),
                        set("BaseColor", "Coat", Map.of("Black", "Black Coat"))),
                entry("global", appliesTo(null, null, null, null),
                        set("Horns", "Horns", Map.of("Small", "Small Horns")))
        ));

        BsonDocument metadata = capturedMetadata("Fluffy", "Mob_Cat", "Fluffy")
                .append(TameworkMetadataKeys.ATTACHMENTS, new BsonString("{\"BaseColor\":\"Black\",\"Horns\":\"Small\"}"));

        TooltipData data = provider.getTooltipData("*Spawner_Test_State_Filled", metadata.toJson(), "en-US");

        assertNotNull(data);
        assertTrue(data.getLines().contains("Coat: Black Coat"));
        assertTrue(data.getLines().contains("Horns: Small Horns"));
    }

    @Test
    void multipleAttachmentLinesAreSortedBySetIdAndUnknownValuesUseRawIds() throws Exception {
        ItemFeatureRegistry registry = registry(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE);
        TameworkSpawnerTooltipProvider provider = provider(registry, displayConfig(
                "Display_Global",
                0,
                true,
                entry("global", appliesTo(null, null, null, null),
                        set("Horns", "Horns", Map.of("Small", "Small Horns")),
                        set("BaseColor", "Coat", Map.of()))
        ));

        BsonDocument metadata = capturedMetadata("Fluffy", "Mob_Cat", "Fluffy")
                .append(TameworkMetadataKeys.ATTACHMENTS, new BsonString("{\"Horns\":\"Small\",\"BaseColor\":\"Black\"}"));

        TooltipData data = provider.getTooltipData("*Spawner_Test_State_Filled", metadata.toJson(), "en-US");

        assertNotNull(data);
        assertEquals("Coat: Black", data.getLines().get(2));
        assertEquals("Horns: Small Horns", data.getLines().get(3));
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

    private static ItemFeatureRegistry registry(ItemFeatureConfig.SpawnerTooltipMode mode) {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("Spawner_Test", ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerFilledItemId("*Spawner_Test_State_Filled")
                .spawnerTooltipMode(mode)
                .build());
        return registry;
    }

    private static TameworkSpawnerTooltipProvider provider(ItemFeatureRegistry registry,
                                                          TwAttachmentDisplayConfig... configs) {
        return new TameworkSpawnerTooltipProvider(
                registry,
                new TranslationRegistry(),
                new AttachmentDisplayResolver(List.of(configs))
        );
    }

    private static TwAttachmentDisplayConfig displayConfig(String id,
                                                          int priority,
                                                          boolean enabled,
                                                          TwAttachmentDisplayConfig.Entry... entries)
            throws Exception {
        TwAttachmentDisplayConfig config = construct(TwAttachmentDisplayConfig.class);
        setField(config, "id", id);
        setField(config, "priority", priority);
        setField(config, "enabled", enabled);
        setField(config, "entries", entries);
        return config;
    }

    private static TwAttachmentDisplayConfig.Entry entry(String id,
                                                        TwAttachmentDisplayConfig.AppliesTo appliesTo,
                                                        Map<String, TwAttachmentDisplayConfig.AttachmentSetDisplay>... sets)
            throws Exception {
        TwAttachmentDisplayConfig.Entry entry = construct(TwAttachmentDisplayConfig.Entry.class);
        setField(entry, "id", id);
        setField(entry, "appliesTo", appliesTo);
        Map<String, TwAttachmentDisplayConfig.AttachmentSetDisplay> merged = new java.util.LinkedHashMap<>();
        for (Map<String, TwAttachmentDisplayConfig.AttachmentSetDisplay> set : sets) {
            merged.putAll(set);
        }
        setField(entry, "sets", merged);
        return entry;
    }

    private static TwAttachmentDisplayConfig.AppliesTo appliesTo(String[] roleIds,
                                                                 String[] modelIds,
                                                                 String[] roleNamespaces,
                                                                 String[] modelNamespaces)
            throws Exception {
        TwAttachmentDisplayConfig.AppliesTo appliesTo = construct(TwAttachmentDisplayConfig.AppliesTo.class);
        if (roleIds != null) setField(appliesTo, "roleIds", roleIds);
        if (modelIds != null) setField(appliesTo, "modelIds", modelIds);
        if (roleNamespaces != null) setField(appliesTo, "roleNamespaces", roleNamespaces);
        if (modelNamespaces != null) setField(appliesTo, "modelNamespaces", modelNamespaces);
        return appliesTo;
    }

    private static Map<String, TwAttachmentDisplayConfig.AttachmentSetDisplay> set(String setId,
                                                                                  String label,
                                                                                  Map<String, String> values)
            throws Exception {
        TwAttachmentDisplayConfig.AttachmentSetDisplay set = construct(
                TwAttachmentDisplayConfig.AttachmentSetDisplay.class
        );
        setField(set, "label", label);
        setField(set, "values", values);
        return Map.of(setId, set);
    }

    private static <T> T construct(Class<T> type) throws Exception {
        Constructor<T> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
