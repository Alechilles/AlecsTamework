package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AvatarFlightRiderModelVariantServiceTest {
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

}
