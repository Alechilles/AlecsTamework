package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.overrides.TwConfigAssetDescriptor;
import com.alechilles.alecstamework.config.overrides.TwConfigFamily;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                true
        );
    }
}

