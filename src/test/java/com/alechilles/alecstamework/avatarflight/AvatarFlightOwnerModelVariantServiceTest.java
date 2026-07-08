package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightOwnerModelVariantServiceTest {
    private static final Path SERVICE = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightOwnerModelVariantService.java"
    );

    @Test
    void ownerVariantRewritesNodeIdsWithoutRenamingAnimationTargets() {
        String rewritten = AvatarFlightOwnerModelVariantService.rewriteBlockymodelJsonForOwner("""
                {
                  "nodes": [
                    {
                      "id": "1",
                      "name": "Origin",
                      "children": [
                        {
                          "id": "2",
                          "name": "Chest",
                          "children": [
                            { "id": "3", "name": "Head", "children": [] },
                            { "id": "4", "name": "R-Hand", "children": [] }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        JsonObject root = JsonParser.parseString(rewritten).getAsJsonObject();
        JsonObject origin = root.getAsJsonArray("nodes").get(0).getAsJsonObject();
        JsonObject chest = origin.getAsJsonArray("children").get(0).getAsJsonObject();
        JsonObject head = chest.getAsJsonArray("children").get(0).getAsJsonObject();
        JsonObject rightHand = chest.getAsJsonArray("children").get(1).getAsJsonObject();

        assertEquals("tw_avatar_owner_1", origin.get("id").getAsString());
        assertEquals("tw_avatar_owner_2", chest.get("id").getAsString());
        assertEquals("tw_avatar_owner_3", head.get("id").getAsString());
        assertEquals("tw_avatar_owner_4", rightHand.get("id").getAsString());
        assertEquals("Origin", origin.get("name").getAsString());
        assertEquals("Chest", chest.get("name").getAsString());
        assertEquals("Head", head.get("name").getAsString());
        assertEquals("R-Hand", rightHand.get("name").getAsString());
        assertFalse(rewritten.contains("\"id\":\"1\""));
    }

    @Test
    void generatedVariantPathStaysUnderOwnerVariants() {
        assertEquals(
                "Tamework/AvatarFlight/Owner/Variants/NPC/HyDragon/NordicDrake/Model/NordicDrake.blockymodel",
                AvatarFlightOwnerModelVariantService.generatedVariantPath(
                        "NPC/HyDragon/NordicDrake/Model/NordicDrake.blockymodel"
                )
        );
        assertTrue(AvatarFlightOwnerModelVariantService.isGeneratedVariant(
                "Tamework/AvatarFlight/Owner/Variants/NPC/HyDragon/NordicDrake/Model/NordicDrake.blockymodel"
        ));
    }

    @Test
    void generatedVariantsRegisterThroughCommonAssetModule() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("new AvatarFlightGeneratedCommonAsset("));
        assertTrue(source.contains("module.addCommonAsset(GENERATED_PACK, generatedAsset, false)"));
        assertFalse(source.contains("CommonAssetRegistry.addCommonAsset(GENERATED_PACK, generatedAsset);\n            CommonAssetModule"));
    }
}
