package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompanionAdultScaleResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void remapNaturalScaleMapsBabyRangeToFixedAdultScale() {
        Double remapped = CompanionAdultScaleResolver.remapNaturalScale(1.0, 0.95, 1.05, 1.2, 1.2);
        assertEquals(1.2, remapped, 0.000001);
    }

    @Test
    void remapNaturalScalePreservesPercentileAcrossDifferentRanges() {
        Double remapped = CompanionAdultScaleResolver.remapNaturalScale(1.0, 0.8, 1.2, 1.4, 2.2);
        assertEquals(1.8, remapped, 0.000001);
    }

    @Test
    void remapNaturalScaleReturnsNullWhenSourceScaleIsOutsideSourceRange() {
        assertNull(CompanionAdultScaleResolver.remapNaturalScale(0.7, 0.95, 1.05, 1.2, 1.2));
    }

    @Test
    void resolveAppearanceFromRoleAssetUsesVariantModifyAppearance() throws IOException {
        Path role = writeRole(
                "Role_With_Modify.json",
                """
                {
                  "Type": "Variant",
                  "Reference": "Template_Livestock_Tamed",
                  "Modify": {
                    "Appearance": "Bison"
                  }
                }
                """
        );

        String appearance = CompanionAdultScaleResolver.resolveAppearanceFromRoleAsset(
                role,
                reference -> tempDir.resolve(reference + ".json")
        );

        assertEquals("Bison", appearance);
    }

    @Test
    void resolveAppearanceFromRoleAssetFallsBackThroughReferenceChain() throws IOException {
        writeRole(
                "Template_Livestock_Tamed.json",
                """
                {
                  "Appearance": "Bison_Template"
                }
                """
        );
        Path role = writeRole(
                "Tamed_Bison.json",
                """
                {
                  "Type": "Variant",
                  "Reference": "Template_Livestock_Tamed"
                }
                """
        );

        String appearance = CompanionAdultScaleResolver.resolveAppearanceFromRoleAsset(
                role,
                reference -> tempDir.resolve(reference + ".json")
        );

        assertEquals("Bison_Template", appearance);
    }

    private Path writeRole(String fileName, String content) throws IOException {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, content);
        return path;
    }
}
