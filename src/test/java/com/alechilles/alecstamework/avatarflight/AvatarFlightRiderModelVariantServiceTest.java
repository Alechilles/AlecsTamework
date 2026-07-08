package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    void rewritesPlayerAnimationNodesToRiderSafeNames() {
        String rewritten = AvatarFlightRiderModelVariantService.rewriteBlockymodelJsonForRider("""
                {
                  "nodes": [
                    {
                      "id": "4",
                      "name": "Pelvis",
                      "children": [
                        {
                          "id": "2",
                          "name": "Chest",
                          "children": [
                            { "id": "1", "name": "Head", "children": [] },
                            { "id": "80", "name": "Helmet_Base", "children": [] },
                            { "id": "8", "name": "R-Thigh", "children": [] }
                          ]
                        }
                      ]
                    }
                  ],
                  "format": "character"
                }
                """);

        JsonObject root = JsonParser.parseString(rewritten).getAsJsonObject();
        JsonObject pelvis = root.getAsJsonArray("nodes").get(0).getAsJsonObject();
        JsonObject chest = firstChild(pelvis);
        JsonArray chestChildren = chest.getAsJsonArray("children");

        assertEquals("tw_rider_attachment_4", pelvis.get("id").getAsString());
        assertEquals("TameworkRider_Pelvis", pelvis.get("name").getAsString());
        assertEquals("tw_rider_attachment_2", chest.get("id").getAsString());
        assertEquals("TameworkRider_Chest", chest.get("name").getAsString());
        assertEquals("Head", chestChildren.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Helmet_Base", chestChildren.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("TameworkRider_R-Thigh", chestChildren.get(2).getAsJsonObject().get("name").getAsString());
        assertFalse(rewritten.contains("\"name\":\"Pelvis\""));
        assertFalse(rewritten.contains("\"id\":\"4\""));
    }

    @Test
    void generatedVariantPathStaysUnderTameworkCommonAssets() {
        assertEquals(
                "Tamework/AvatarFlight/Rider/Variants/Items/Armors/Iron/Chest.blockymodel",
                AvatarFlightRiderModelVariantService.generatedVariantPath(
                        "Items/Armors/Iron/Chest.blockymodel"
                )
        );
        assertTrue(AvatarFlightRiderModelVariantService.generatedVariantPath(
                "Tamework/AvatarFlight/Rider/Variants/Items/Armors/Iron/Chest.blockymodel"
        ).startsWith("Tamework/AvatarFlight/Rider/Variants/"));
        assertTrue(AvatarFlightRiderModelVariantService.isGeneratedVariant(
                "Tamework/AvatarFlight/Rider/Equipment/Items/Armors/Iron/Chest.blockymodel"
        ));
    }

    @Test
    void generatedVariantsRegisterThroughCommonAssetModule() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("new AvatarFlightGeneratedCommonAsset("));
        assertTrue(source.contains("module.addCommonAsset(GENERATED_PACK, generatedAsset, false)"));
        assertFalse(source.contains("CommonAssetRegistry.addCommonAsset(GENERATED_PACK, generatedAsset);\n            CommonAssetModule"));
    }

    private static JsonObject firstChild(JsonObject node) {
        return node.getAsJsonArray("children").get(0).getAsJsonObject();
    }
}
