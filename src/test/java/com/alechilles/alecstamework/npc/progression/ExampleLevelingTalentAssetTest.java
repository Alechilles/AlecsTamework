package com.alechilles.alecstamework.npc.progression;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the bundled example role XP/talent assets from regressing back to an inert leveling system.
 */
class ExampleLevelingTalentAssetTest {
    private static final Set<String> EXAMPLE_ROLES = Set.of(
            "Mob_Tamework_Example",
            "Mob_Tamework_Example_Baby",
            "Mob_Tamework_Example_Vanilla",
            "Mob_Tamework_Example_Simple",
            "Mob_Tamework_Example_Simple_Vanilla",
            "Mob_Tamework_Example_Patch"
    );

    @Test
    void exampleLevelingConfigEnablesXpForAllExampleRoles() {
        JsonObject asset = readJson("Server/Tamework/Leveling/TwLevelingExample.json");

        assertTrue(asset.get("Enabled").getAsBoolean());
        assertEquals(EXAMPLE_ROLES, roleIds(asset));
        JsonObject sources = asset.getAsJsonObject("XpSources");
        assertEnabledFlatSource(sources, "Feed");
        assertEnabledFlatSource(sources, "Harvest");
        assertEnabledFlatSource(sources, "Breeding");
        JsonObject combat = sources.getAsJsonObject("Combat");
        assertTrue(combat.get("Enabled").getAsBoolean());
        assertTrue(combat.get("DamageDealtXpPerPoint").getAsDouble() > 0.0);
        assertTrue(combat.get("DamageTakenXpPerPoint").getAsDouble() > 0.0);
        assertFalse(combat.get("AwardVsPlayers").getAsBoolean());
        assertFalse(combat.get("AwardVsOwnedAllies").getAsBoolean());
    }

    @Test
    void exampleTalentConfigDefinesPurchasablePassiveNodesForAllExampleRoles() {
        JsonObject asset = readJson("Server/Tamework/Talents/TwTalentsExample.json");

        assertTrue(asset.get("Enabled").getAsBoolean());
        assertEquals(EXAMPLE_ROLES, roleIds(asset));
        JsonArray talents = asset.getAsJsonArray("Talents");
        assertNotNull(talents);
        assertTrue(talents.size() >= 3, "Example talent tree should have enough nodes to exercise the UI.");
        for (int i = 0; i < talents.size(); i++) {
            JsonObject talent = talents.get(i).getAsJsonObject();
            assertTrue(talent.has("Id"));
            assertTrue(talent.has("DisplayName"));
            assertTrue(talent.has("PointCost"));
            assertTrue(talent.get("PointCost").getAsInt() > 0);
            assertTrue(talent.has("Effects"));
            assertFalse(talent.getAsJsonArray("Effects").isEmpty());
        }
    }

    private static void assertEnabledFlatSource(JsonObject sources, String key) {
        JsonObject source = sources.getAsJsonObject(key);
        assertNotNull(source, key + " source must be present.");
        assertTrue(source.get("Enabled").getAsBoolean(), key + " XP source must be enabled.");
        assertTrue(source.get("FlatXp").getAsDouble() > 0.0, key + " XP source must award XP.");
    }

    private static Set<String> roleIds(JsonObject asset) {
        return StreamSupport.stream(asset.getAsJsonArray("RoleIds").spliterator(), false)
                .map(entry -> entry.getAsString())
                .collect(Collectors.toSet());
    }

    private static JsonObject readJson(String resourcePath) {
        InputStream stream = ExampleLevelingTalentAssetTest.class.getClassLoader().getResourceAsStream(resourcePath);
        assertNotNull(stream, "Missing resource: " + resourcePath);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            throw new AssertionError("Could not parse " + resourcePath, exception);
        }
    }
}
