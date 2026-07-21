package com.alechilles.alecstamework.config.overrides;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwConfigFamilyHydragonTest {

    @Test
    void capturePolicyUsesStableEditorIdentityAndAssetPath() {
        TwConfigFamily family = TwConfigFamily.CAPTURE_POLICY;

        assertEquals("capture-policy", family.getId());
        assertEquals("Capture Policies", family.getDisplayName());
        assertEquals("Tamework/CapturePolicies", family.getStorePath());
        assertEquals("Server/Tamework/CapturePolicies", family.getServerRelativePrefix());
        assertEquals(family, TwConfigFamily.fromStorePath("tamework/capturepolicies"));
        assertTrue(family.isEditableInV1());
        assertTrue(family.isKnownType());
    }

    @Test
    void populationGroupUsesStableEditorIdentityAndAssetPath() {
        TwConfigFamily family = TwConfigFamily.POPULATION_GROUP;

        assertEquals("population-group", family.getId());
        assertEquals("Population Groups", family.getDisplayName());
        assertEquals("Tamework/PopulationGroups", family.getStorePath());
        assertEquals("Server/Tamework/PopulationGroups", family.getServerRelativePrefix());
        assertEquals(family, TwConfigFamily.fromStorePath(" Tamework/PopulationGroups "));
        assertTrue(family.isEditableInV1());
        assertTrue(family.isKnownType());
    }

    @Test
    void fallbackSourcePathsStayInsideCanonicalFamilyDirectories() {
        assertEquals(
                Path.of("Server/Tamework/CapturePolicies/Config_Hydragon_CapturePolicy.json"),
                TwConfigOverrideManager.resolveRelativeServerPath(
                        null,
                        TwConfigFamily.CAPTURE_POLICY.getStorePath(),
                        "Hydragon_CapturePolicy"
                )
        );
        assertEquals(
                Path.of("Server/Tamework/PopulationGroups/Config_Hydragon_FullDragons.json"),
                TwConfigOverrideManager.resolveRelativeServerPath(
                        null,
                        TwConfigFamily.POPULATION_GROUP.getStorePath(),
                        "Hydragon_FullDragons"
                )
        );
    }
}
