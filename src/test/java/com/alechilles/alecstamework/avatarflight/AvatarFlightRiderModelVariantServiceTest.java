package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderModelVariantServiceTest {
    private static final Path SERVICE = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightRiderModelVariantService.java"
    );

    @Test
    void resolveForRiderKeepsOriginalAttachmentPathsForThirdPartyCompatibility() {
        assertEquals(
                "Items/Armors/Iron/Chest.blockymodel",
                AvatarFlightRiderModelVariantService.resolveForRider(
                        "Common/Items/Armors/Iron/Chest.blockymodel"
                )
        );
        assertEquals(
                "ModdedPack/Items/DragonArmor/Chest.blockymodel",
                AvatarFlightRiderModelVariantService.resolveForRider(
                        "/Common/ModdedPack/Items/DragonArmor/Chest.blockymodel"
                )
        );
    }

    @Test
    void oldGeneratedVariantPathsAreStillRecognizedForCleanup() {
        assertTrue(AvatarFlightRiderModelVariantService.isGeneratedVariant(
                "Tamework/AvatarFlight/Rider/Variants/Items/Armors/Iron/Chest.blockymodel"
        ));
        assertTrue(AvatarFlightRiderModelVariantService.isGeneratedVariant(
                "Tamework/AvatarFlight/Rider/Equipment/Items/Armors/Iron/Chest.blockymodel"
        ));
    }

    @Test
    void riderResolutionDoesNotRegisterRuntimeCommonAssets() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertFalse(source.contains("CommonAssetModule"));
        assertFalse(source.contains("CommonAssetRegistry"));
        assertFalse(source.contains("addCommonAsset("));
        assertFalse(source.contains("AvatarFlightGeneratedCommonAsset"));
        assertFalse(source.contains("getBlob().join"));
        assertFalse(source.contains("maybeGenerateVariant"));
    }
}
