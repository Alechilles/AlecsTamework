package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.overrides.TwConfigAssetDescriptor;
import com.alechilles.alecstamework.config.overrides.TwConfigFamily;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwConfigSchemaAdapterTest {

    @Test
    void globalSchemaIncludesNestedSectionFields() {
        TwConfigAssetDescriptor descriptor = descriptor(TwConfigFamily.GLOBAL, "TwGlobalConfig_Default");

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigSchemaAdapter.fieldsFor(descriptor);

        assertFalse(fields.isEmpty());
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Parent"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "General.Enabled"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "OwnershipProtection.BlockOwnerDamage"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "SimpleClaims.Breeding.LimitPerClaimChunk"));
        assertNull(TwConfigEditorFieldPolicy.findField(fields, "SimpleClaims.Provider"));

        TwConfigEditorFieldPolicy.EditorFieldSpec parent = TwConfigEditorFieldPolicy.findField(fields, "Parent");
        assertNotNull(parent);
        assertFalse(parent.tooltip().isBlank());

        TwConfigEditorFieldPolicy.EditorFieldSpec tags =
                TwConfigEditorFieldPolicy.findField(fields, "Tags");
        assertNotNull(tags);
        assertFalse(tags.tooltip().isBlank());
    }

    @Test
    void breedingSchemaLoadsWithoutFallingBackToCrossAssetShape() {
        TwConfigAssetDescriptor descriptor = descriptor(TwConfigFamily.BREEDING, "TwBreedingConfig_Default");

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigSchemaAdapter.fieldsFor(descriptor);

        assertFalse(fields.isEmpty());
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "OffspringLifecycle.Enabled"));
        TwConfigEditorFieldPolicy.EditorFieldSpec roleOverrides =
                TwConfigEditorFieldPolicy.findField(fields, "RoleOverrides");
        assertNotNull(roleOverrides);
        assertTrue(roleOverrides.handoffOnly());
    }

    @Test
    void spawnerSchemaExposesTopLevelFieldTooltips() {
        TwConfigAssetDescriptor descriptor = descriptor(TwConfigFamily.SPAWNER, "TwSpawnerConfig_Default");

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigSchemaAdapter.fieldsFor(descriptor);

        assertFalse(fields.isEmpty());
        TwConfigEditorFieldPolicy.EditorFieldSpec emptyItemId =
                TwConfigEditorFieldPolicy.findField(fields, "EmptyItemId");
        assertNotNull(emptyItemId);
        assertFalse(emptyItemId.tooltip().isBlank());
    }

    @Test
    void namesSchemaIncludesExpectedNamePoolSections() {
        TwConfigAssetDescriptor descriptor = descriptor(TwConfigFamily.NAMES, "TwNames_Default");

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigSchemaAdapter.fieldsFor(descriptor);

        assertFalse(fields.isEmpty());
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "NorthAmericaMale"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "NorthAmericaFemale"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "GermanMale"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "GermanFemale"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "SpanishMale"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "SpanishFemale"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "BrazilianPortugueseMale"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "BrazilianPortugueseFemale"));
    }

    @Test
    void dynamicAttachmentsSchemaExposesRulesAndConditionDefaults() {
        assertEquals("Tamework/DynamicAttachments", TwConfigFamily.DYNAMIC_ATTACHMENTS.getStorePath());

        TwConfigAssetDescriptor descriptor =
                descriptor(TwConfigFamily.DYNAMIC_ATTACHMENTS, "TwDynamicAttachments_Default");

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigSchemaAdapter.fieldsFor(descriptor);

        assertFalse(fields.isEmpty());
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "RoleIds"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Rules"));
        TwConfigEditorFieldPolicy.EditorFieldSpec ignoreCase =
                TwConfigEditorFieldPolicy.findField(fields, "Rules.Conditions.IgnoreCase");
        assertNotNull(ignoreCase);
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.BOOLEAN, ignoreCase.type());
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Rules.Conditions.Values"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Rules.Conditions.Percent"));
    }

    @Test
    void capturePolicySchemaIsEditableWithInheritanceTooltips() {
        TwConfigAssetDescriptor descriptor =
                descriptor(TwConfigFamily.CAPTURE_POLICY, "Hydragon_CapturePolicy");

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields =
                TwConfigSchemaAdapter.fieldsFor(descriptor);

        assertFalse(fields.isEmpty());
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Parent"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Enabled"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Priority"));
        TwConfigEditorFieldPolicy.EditorFieldSpec roleIds =
                TwConfigEditorFieldPolicy.findField(fields, "RoleIds");
        assertNotNull(roleIds);
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.STRING_LIST, roleIds.type());
        assertTrue(roleIds.tooltip().contains("replaces parent"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Difficulty.MinimumPower"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Difficulty.Resistance"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Difficulty.ChanceMultiplier"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Difficulty.MissingHealthBonus"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Difficulty.GuaranteedAtPower"));
        TwConfigEditorFieldPolicy.EditorFieldSpec requirements =
                TwConfigEditorFieldPolicy.findField(fields, "Requirements");
        assertNotNull(requirements);
        assertTrue(requirements.handoffOnly());
        assertTrue(requirements.tooltip().contains("replaces parent"));
    }

    private static TwConfigAssetDescriptor descriptor(TwConfigFamily family, String assetId) {
        return new TwConfigAssetDescriptor(
                family,
                family.getId(),
                family.getDisplayName(),
                family.getStorePath(),
                assetId,
                "pack-test",
                null,
                Path.of("source", assetId + ".json"),
                Path.of("Server", "Tamework", family.getStorePath(), assetId + ".json"),
                true,
                true,
                false
        );
    }
}
