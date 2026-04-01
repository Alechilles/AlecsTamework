package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.overrides.TwConfigAssetDescriptor;
import com.alechilles.alecstamework.config.overrides.TwConfigFamily;
import com.alechilles.alecstamework.config.overrides.TwConfigSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwConfigEditorFieldPolicyTest {

    @Test
    void assetEditableRequiresKnownAndDescriptorEditable() {
        assertTrue(TwConfigEditorFieldPolicy.isAssetEditable(descriptor(TwConfigFamily.GLOBAL, true, true)));
        assertFalse(TwConfigEditorFieldPolicy.isAssetEditable(descriptor(TwConfigFamily.GLOBAL, false, true)));
        assertFalse(TwConfigEditorFieldPolicy.isAssetEditable(descriptor(TwConfigFamily.GLOBAL, true, false)));
    }

    @Test
    void fallbackInspectorBuildsTypedEditablePrimitivesWhenSchemaUnavailable() {
        JsonObject effective = new JsonObject();
        effective.addProperty("Enabled", true);
        effective.addProperty("Priority", 10);

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.OTHER, false, false),
                null,
                effective
        );

        assertFalse(fields.isEmpty());
        assertTrue(fields.stream().anyMatch(TwConfigEditorFieldPolicy.EditorFieldSpec::isEditableValue));
    }

    @Test
    void fallbackInspectorRecursesNestedObjectsAndBuildsTypedEditableFields() {
        JsonObject effective = new JsonObject();
        effective.addProperty("Parent", "ParentAsset");
        effective.addProperty("$Title", "hidden");

        JsonObject general = new JsonObject();
        general.addProperty("Enabled", true);
        general.addProperty("Priority", 3);
        general.addProperty("Radius", 5.5);
        general.addProperty("Comment", "text");
        JsonArray tags = new JsonArray();
        tags.add("one");
        tags.add("two");
        general.add("Tags", tags);
        effective.add("General", general);

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.OTHER, false, false),
                null,
                effective
        );

        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Parent"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "General.Enabled"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "General.Priority"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "General.Radius"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "General.Comment"));

        TwConfigEditorFieldPolicy.EditorFieldSpec tagsField =
                TwConfigEditorFieldPolicy.findField(fields, "General.Tags");
        assertNotNull(tagsField);
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.STRING_LIST, tagsField.type());
        assertTrue(tagsField.isEditableValue());

        assertTrue(fields.stream().noneMatch(field -> field.path().startsWith("$")));
        assertTrue(fields.stream().noneMatch(field -> field.path().equalsIgnoreCase("General")));
    }

    @Test
    void parentOptionsAreScopedToSameFamilyAcrossPacks() {
        TwConfigAssetDescriptor self = descriptor(TwConfigFamily.INTERACTION, true, true, "interaction", "pack-A", "Interaction_A");
        TwConfigAssetDescriptor sibling = descriptor(TwConfigFamily.INTERACTION, true, true, "interaction", "pack-A", "Interaction_B");
        TwConfigAssetDescriptor otherPack = descriptor(TwConfigFamily.INTERACTION, true, true, "interaction", "pack-B", "Interaction_C");
        TwConfigAssetDescriptor unknownType = descriptor(TwConfigFamily.INTERACTION, false, true, "interaction", "pack-C", "Interaction_Unknown");
        TwConfigAssetDescriptor otherFamily = descriptor(TwConfigFamily.SPAWNER, true, true, "spawner", "pack-A", "Spawner_A");

        TwConfigSnapshot snapshot = new TwConfigSnapshot(
                Path.of("overrides"),
                "hash",
                List.of(self, sibling, otherPack, unknownType, otherFamily),
                new LinkedHashMap<>()
        );

        TwConfigEditorFieldPolicy.EditorFieldSpec parentField = TwConfigEditorFieldPolicy.parentField(0);
        List<String> options = TwConfigEditorFieldPolicy.optionsFor(parentField, self, snapshot);
        assertTrue(options.contains("Interaction_B"));
        assertTrue(options.contains("Interaction_C"));
        assertFalse(options.contains("Interaction_A"));
        assertFalse(options.contains("Interaction_Unknown"));
        assertFalse(options.contains("Spawner_A"));
    }

    @Test
    void findFieldMatchesCaseInsensitiveIds() {
        TwConfigEditorFieldPolicy.EditorFieldSpec parent = TwConfigEditorFieldPolicy.parentField(0);
        TwConfigEditorFieldPolicy.EditorFieldSpec found =
                TwConfigEditorFieldPolicy.findField(List.of(parent), "PARENT");
        assertNotNull(found);
        assertTrue(found.parentSelector());
    }

    private static TwConfigAssetDescriptor descriptor(TwConfigFamily family,
                                                      boolean knownType,
                                                      boolean editable) {
        return descriptor(family, knownType, editable, family.getId(), "pack-A", family.name() + "_Default");
    }

    private static TwConfigAssetDescriptor descriptor(TwConfigFamily family,
                                                      boolean knownType,
                                                      boolean editable,
                                                      String familyKey,
                                                      String sourcePackKey,
                                                      String assetId) {
        return new TwConfigAssetDescriptor(
                family,
                familyKey,
                family.getDisplayName(),
                family.getStorePath(),
                assetId,
                sourcePackKey,
                null,
                Path.of("source", assetId + ".json"),
                Path.of("Server", "Tamework", assetId + ".json"),
                editable,
                knownType,
                false
        );
    }
}
