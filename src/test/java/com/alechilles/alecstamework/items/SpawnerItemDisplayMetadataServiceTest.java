package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwAttachmentDisplayConfig;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.attachments.AttachmentDisplayResolver;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerItemDisplayMetadataServiceTest {

    @Test
    void additiveModeWritesBaseDescriptionAndCapturedLines() {
        CapturingDisplayMetadataWriter writer = new CapturingDisplayMetadataWriter();
        SpawnerItemDisplayMetadataService service = service(writer, Message.raw("Base description"));
        ItemStack stack = stack(capturedMetadata("Fluffy", "Mob_Cat", "Fluffy"));

        ItemStack updated = service.applyCapturedDisplayMetadata(stack, config(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE));
        ItemDisplayMetadata metadata = writer.metadata;

        assertSame(stack, updated);
        assertNotNull(metadata);
        assertEquals("Fluffy (Mob_Cat)", metadata.getName().getAnsiMessage());
        assertEquals(
                "Base description\n\nFluffy - Mob_Cat",
                plainText(metadata.getDescription())
        );
    }

    @Test
    void replaceModeWritesOnlyCapturedLines() {
        CapturingDisplayMetadataWriter writer = new CapturingDisplayMetadataWriter();
        SpawnerItemDisplayMetadataService service = service(writer, Message.raw("Base description"));
        ItemStack stack = stack(capturedMetadata("Fluffy", "Mob_Cat", "Fluffy"));

        ItemStack updated = service.applyCapturedDisplayMetadata(stack, config(ItemFeatureConfig.SpawnerTooltipMode.REPLACE));
        ItemDisplayMetadata metadata = writer.metadata;

        assertSame(stack, updated);
        assertNotNull(metadata);
        assertEquals("Fluffy (Mob_Cat)", metadata.getName().getAnsiMessage());
        assertEquals("Fluffy - Mob_Cat", plainText(metadata.getDescription()));
    }

    @Test
    void roleNameKeyMetadataResolvesTamedRoleDisplay() {
        TranslationRegistry translations = new TranslationRegistry();
        translations.put("npcRoles.Bison.name", "Bison");
        CapturingDisplayMetadataWriter writer = new CapturingDisplayMetadataWriter();
        SpawnerItemDisplayMetadataService service = service(translations, null, Message.raw("Base description"), writer);
        BsonDocument metadata = capturedMetadata("Tamed_Bison", "Tamed_Bison", null)
                .append(TameworkMetadataKeys.CAPTURE_NAME_KEY, new BsonString("server.npcRoles.Bison.name"));

        ItemStack updated = service.applyCapturedDisplayMetadata(
                stack(metadata),
                config(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE)
        );
        ItemDisplayMetadata display = writer.metadata;

        assertNotNull(display);
        assertEquals("Bison", display.getName().getAnsiMessage());
        assertEquals("Base description\n\nBison", plainText(display.getDescription()));
    }

    @Test
    void baseRoleIdentifierDoesNotBecomeAFalseCustomName() {
        TranslationRegistry translations = new TranslationRegistry();
        translations.put("npcRoles.Wolf_Black.name", "Black Wolf");
        CapturingDisplayMetadataWriter writer =
                new CapturingDisplayMetadataWriter();
        SpawnerItemDisplayMetadataService service = service(
                translations,
                null,
                Message.raw("Base description"),
                writer
        );
        BsonDocument metadata = capturedMetadata(
                "Wolf_Black", "Tamed_Wolf_Black", null
        ).append(
                TameworkMetadataKeys.CAPTURE_NAME_KEY,
                new BsonString("server.npcRoles.Wolf_Black.name")
        );

        service.applyCapturedDisplayMetadata(
                stack(metadata),
                config(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE)
        );

        assertNotNull(writer.metadata);
        assertEquals(
                "Black Wolf",
                writer.metadata.getName().getAnsiMessage()
        );
        assertEquals(
                "Base description\n\nBlack Wolf",
                plainText(writer.metadata.getDescription())
        );
    }

    @Test
    void genericCapturedDisplayNameFallsBackToRoleOnly() {
        CapturingDisplayMetadataWriter writer = new CapturingDisplayMetadataWriter();
        SpawnerItemDisplayMetadataService service = service(writer, null);
        BsonDocument metadata = capturedMetadata("Capture Crate", "Cat", null);

        ItemStack updated = service.applyCapturedDisplayMetadata(
                stack(metadata),
                config(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE)
        );
        ItemDisplayMetadata display = writer.metadata;

        assertNotNull(display);
        assertEquals("Cat", display.getName().getAnsiMessage());
        assertEquals("Cat", plainText(display.getDescription()));
    }

    @Test
    void writesGenderAndResolvedAttachmentLines() throws Exception {
        CapturingDisplayMetadataWriter writer = new CapturingDisplayMetadataWriter();
        SpawnerItemDisplayMetadataService service = service(
                new TranslationRegistry(),
                new AttachmentDisplayResolver(List.of(displayConfig(
                        "Display_Model",
                        0,
                        true,
                        entry("model", appliesTo(null, new String[] { "Model_Cat" }, null, null),
                                set("BaseColor", "Coat", Map.of("Black", "Black Coat")))
                ))),
                null,
                writer
        );
        BsonDocument metadata = capturedMetadata("Fluffy", "Mob_Cat", "Fluffy")
                .append(TameworkMetadataKeys.LIFE_STAGE_GENDER, new BsonString("Male"))
                .append(TameworkMetadataKeys.CAPTURE_MODEL_ID, new BsonString("Model_Cat"))
                .append(TameworkMetadataKeys.ATTACHMENTS, new BsonString("{\"BaseColor\":\"Black\"}"));

        ItemStack updated = service.applyCapturedDisplayMetadata(
                stack(metadata),
                config(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE)
        );
        String description = plainText(writer.metadata.getDescription());

        assertEquals(
                "Fluffy - Mob_Cat (M)\n\n-- Appearance --\nCoat: Black Coat",
                description
        );
        assertEquals("#63A9FF", messageWithText(writer.metadata.getDescription(), "M").getColor());
    }

    @Test
    void writesColoredProgressionSummaryTraitsAndAppearance() throws Exception {
        TranslationRegistry translations = new TranslationRegistry();
        CapturingDisplayMetadataWriter writer = new CapturingDisplayMetadataWriter();
        TwTraitConfig traitConfig = traitConfig(
                "Traits_Sheep",
                trait("Trait_Size", "Size", 0.65, 1.0, 1.35),
                trait("Trait_Temperament", "Temperament", 0.5, 1.0, 1.5)
        );
        TwLevelingConfig levelingConfig = levelingConfig("Levels_Sheep", 25);
        SpawnerTooltipPresentationService presentation = new SpawnerTooltipPresentationService(
                translations,
                id -> traitConfig,
                role -> traitConfig,
                id -> levelingConfig,
                role -> levelingConfig
        );
        SpawnerItemDisplayMetadataService service = new SpawnerItemDisplayMetadataService(
                translations,
                new AttachmentDisplayResolver(List.of(displayConfig(
                        "Display_Sheep",
                        0,
                        true,
                        entry("sheep", appliesTo(new String[] { "Mob_Sheep" }, null, null, null),
                                set("Fleece", "Fleece", Map.of("White", "White")))
                ))),
                stack -> null,
                writer,
                presentation
        );
        BsonDocument metadata = capturedMetadata("Jane", "Mob_Sheep", "Jane")
                .append(TameworkMetadataKeys.LIFE_STAGE_GENDER, new BsonString("Female"))
                .append(TameworkMetadataKeys.LEVELING_CONFIG_ID, new BsonString("Levels_Sheep"))
                .append(TameworkMetadataKeys.LEVELING_LEVEL, new BsonInt32(15))
                .append(TameworkMetadataKeys.TRAITS_CONFIG_ID, new BsonString("Traits_Sheep"))
                .append(TameworkMetadataKeys.TRAITS_VALUES, new BsonString(
                        "[{\"id\":\"Trait_Size\",\"value\":1.1925},"
                                + "{\"id\":\"Trait_Temperament\",\"value\":0.75}]"
                ))
                .append(TameworkMetadataKeys.ATTACHMENTS, new BsonString("{\"Fleece\":\"White\"}"));

        service.applyCapturedDisplayMetadata(
                stack(metadata),
                config(ItemFeatureConfig.SpawnerTooltipMode.REPLACE)
        );
        Message description = writer.metadata.getDescription();

        assertEquals(
                "Jane - Mob_Sheep (F) - Level 15/25\n\n"
                        + "-- Traits --\n"
                        + "Size: 1.19/1.35 (+55%)\n"
                        + "Temperament: 0.75/1.50 (-50%)\n\n"
                        + "-- Appearance --\n"
                        + "Fleece: White",
                plainText(description)
        );
        assertEquals("#FF8FBD", messageWithText(description, "F").getColor());
        assertEquals("#A2E8AE", messageWithText(description, "1.19").getColor());
        assertEquals("#A2E8AE", messageWithText(description, "+55%").getColor());
        assertEquals("#FFAEAE", messageWithText(description, "0.75").getColor());
        assertEquals("#FFAEAE", messageWithText(description, "-50%").getColor());
        assertEquals("#FFFFFF", messageWithText(description, "/1.35 (").getColor());
        assertEquals("#F6C453", messageWithText(description, "-- Traits --").getColor());
        assertEquals("#74D7E8", messageWithText(description, "-- Appearance --").getColor());
    }

    @Test
    void malformedAttachmentMetadataDoesNotBlockDisplayMetadata() throws Exception {
        CapturingDisplayMetadataWriter writer = new CapturingDisplayMetadataWriter();
        SpawnerItemDisplayMetadataService service = service(
                new TranslationRegistry(),
                new AttachmentDisplayResolver(List.of(displayConfig(
                        "Display_Global",
                        0,
                        true,
                        entry("global", appliesTo(null, null, null, null),
                                set("BaseColor", "Coat", Map.of("Black", "Black Coat")))
                ))),
                null,
                writer
        );
        BsonDocument metadata = capturedMetadata("Fluffy", "Mob_Cat", "Fluffy")
                .append(TameworkMetadataKeys.ATTACHMENTS, new BsonString("not-json"));

        ItemStack updated = service.applyCapturedDisplayMetadata(
                stack(metadata),
                config(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE)
        );

        assertEquals("Fluffy - Mob_Cat", plainText(writer.metadata.getDescription()));
    }

    @Test
    void clearDisplayMetadataUsesNativeDisplayWriter() {
        CapturingDisplayMetadataWriter writer = new CapturingDisplayMetadataWriter();
        SpawnerItemDisplayMetadataService service = service(writer, null);
        ItemStack stack = stack(capturedMetadata("Fluffy", "Mob_Cat", "Fluffy"));

        ItemStack cleared = service.clearDisplayMetadata(stack);

        assertSame(stack, cleared);
        assertSame(stack, writer.stack);
        assertEquals(null, writer.metadata);
    }

    @Test
    void nonCapturedMetadataLeavesStackUnchanged() {
        CapturingDisplayMetadataWriter writer = new CapturingDisplayMetadataWriter();
        SpawnerItemDisplayMetadataService service = service(writer, null);
        ItemStack stack = stack(new BsonDocument(TameworkMetadataKeys.CAPTURED, BsonBoolean.FALSE));

        ItemStack updated = service.applyCapturedDisplayMetadata(
                stack,
                config(ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE)
        );

        assertTrue(updated == stack);
        assertEquals(null, writer.metadata);
    }

    private static SpawnerItemDisplayMetadataService service(CapturingDisplayMetadataWriter writer,
                                                            Message baseDescription) {
        return service(new TranslationRegistry(), null, baseDescription, writer);
    }

    private static SpawnerItemDisplayMetadataService service(Message baseDescription) {
        return service(new TranslationRegistry(), null, baseDescription);
    }

    private static SpawnerItemDisplayMetadataService service(TranslationRegistry translations,
                                                            AttachmentDisplayResolver resolver,
                                                            Message baseDescription) {
        return service(translations, resolver, baseDescription, new CapturingDisplayMetadataWriter());
    }

    private static SpawnerItemDisplayMetadataService service(TranslationRegistry translations,
                                                            AttachmentDisplayResolver resolver,
                                                            Message baseDescription,
                                                            CapturingDisplayMetadataWriter writer) {
        return new SpawnerItemDisplayMetadataService(
                translations,
                resolver,
                stack -> baseDescription,
                writer
        );
    }

    private static ItemStack stack(BsonDocument metadata) {
        try {
            ItemStack stack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
            setItemStackField(stack, "itemId", "Spawner_Test_State_Filled");
            setItemStackField(stack, "quantity", 1);
            setItemStackField(stack, "metadata", metadata);
            return stack;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to construct test ItemStack", ex);
        }
    }

    private static ItemFeatureConfig config(ItemFeatureConfig.SpawnerTooltipMode mode) {
        return ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerTooltipMode(mode)
                .build();
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

    private static TwTraitConfig traitConfig(String id, TwTraitConfig.TraitDefinition... definitions)
            throws Exception {
        TwTraitConfig config = construct(TwTraitConfig.class);
        setField(config, "id", id);
        setField(config, "traits", definitions);
        return config;
    }

    private static TwTraitConfig.TraitDefinition trait(String id,
                                                       String displayName,
                                                       double min,
                                                       double defaultValue,
                                                       double max) throws Exception {
        TwTraitConfig.TraitDefinition definition = construct(TwTraitConfig.TraitDefinition.class);
        setField(definition, "id", id);
        setField(definition, "displayName", displayName);
        setField(definition, "breedingMin", min);
        setField(definition, "defaultValue", defaultValue);
        setField(definition, "breedingMax", max);
        return definition;
    }

    private static TwLevelingConfig levelingConfig(String id, int maxLevel) throws Exception {
        TwLevelingConfig config = construct(TwLevelingConfig.class);
        TwLevelingConfig.LevelSettings levels = construct(TwLevelingConfig.LevelSettings.class);
        setField(levels, "maxLevel", maxLevel);
        setField(config, "id", id);
        setField(config, "levels", levels);
        return config;
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

    private static void setItemStackField(ItemStack target, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = ItemStack.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static String plainText(Message message) {
        if (message == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (message.getRawText() != null) {
            text.append(message.getRawText());
        }
        for (Message child : message.getChildren()) {
            text.append(plainText(child));
        }
        return text.toString();
    }

    private static Message messageWithText(Message message, String expectedText) {
        if (expectedText.equals(message.getRawText())) {
            return message;
        }
        for (Message child : message.getChildren()) {
            Message match = messageWithTextOrNull(child, expectedText);
            if (match != null) {
                return match;
            }
        }
        throw new AssertionError("No message segment found for text: " + expectedText);
    }

    private static Message messageWithTextOrNull(Message message, String expectedText) {
        if (expectedText.equals(message.getRawText())) {
            return message;
        }
        for (Message child : message.getChildren()) {
            Message match = messageWithTextOrNull(child, expectedText);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static final class CapturingDisplayMetadataWriter
            implements SpawnerItemDisplayMetadataService.ItemDisplayMetadataWriter {
        private ItemStack stack;
        private ItemDisplayMetadata metadata;

        @Override
        public ItemStack write(ItemStack stack, ItemDisplayMetadata metadata) {
            this.stack = stack;
            this.metadata = metadata;
            return stack;
        }
    }
}
