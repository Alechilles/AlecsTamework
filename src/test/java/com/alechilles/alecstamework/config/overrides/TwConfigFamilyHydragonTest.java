package com.alechilles.alecstamework.config.overrides;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TwConfigFamilyHydragonTest {

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
                Path.of(
                        "Server/Tamework/PopulationGroups/"
                                + "Config_Hydragon_FullDragons.json"
                ),
                TwConfigOverrideManager.resolveRelativeServerPath(
                        null,
                        TwConfigFamily.POPULATION_GROUP.getStorePath(),
                        "Hydragon_FullDragons"
                )
        );
    }
}
