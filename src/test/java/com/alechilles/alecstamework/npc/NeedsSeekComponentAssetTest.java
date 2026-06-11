package com.alechilles.alecstamework.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Tests asset wiring for needs-seek movement behavior. */
class NeedsSeekComponentAssetTest {

    @Test
    void needsSeekUsesPathfinderOnlyUntilConsumeRange() {
        String content = readResource("Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json");
        JsonObject component = object(content);

        JsonObject parameters = component.getAsJsonObject("Parameters");
        assertNotNull(parameters, "Needs seek component must declare parameters");
        JsonObject pathfinder = parameters.getAsJsonObject("NeedsSeekUsePathfinder");
        JsonObject steering = parameters.getAsJsonObject("NeedsSeekUseSteering");
        JsonObject bestPath = parameters.getAsJsonObject("NeedsSeekUseBestPath");
        JsonObject reachable = parameters.getAsJsonObject("NeedsSeekReachable");
        assertNotNull(pathfinder, "Needs seek component must expose NeedsSeekUsePathfinder");
        assertNotNull(steering, "Needs seek component must expose NeedsSeekUseSteering");
        assertNotNull(bestPath, "Needs seek component must expose NeedsSeekUseBestPath");
        assertNotNull(reachable, "Needs seek component must expose NeedsSeekReachable");
        assertTrue(pathfinder.get("Value").getAsBoolean(), "Needs seek must use pathfinding");
        assertFalse(steering.get("Value").getAsBoolean(), "Needs seek must not direct-steer around pathfinding");
        assertFalse(bestPath.get("Value").getAsBoolean(), "Needs seek must not accept partial best-path fallback");
        assertTrue(reachable.get("Value").getAsBoolean(), "Needs seek must require direct reachability at goal");
        assertTrue(content.contains("\"Compute\": \"NeedsSeekConsumeMaintainMaxDistance\""),
                "Seek motion must stay active until consume range");
        assertEquals(1, countOccurrences(content, "\"Type\": \"MaintainDistance\""),
                "Needs seek may only use maintain-distance after consume delay begins");
    }

    @Test
    void examplesUseSharedNeedsSeekComponentDefaults() {
        String tameworkExample = readResource("Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json");
        String vanillaExample = readResource("Server/NPC/Roles/_Core/Templates/Template_Tamework_Example_Vanilla.json");

        assertTrue(tameworkExample.contains("\"Reference\": \"Component_Tamework_Instruction_Needs_Seek_Resource\""));
        assertTrue(vanillaExample.contains("\"Reference\": \"Component_Tamework_Instruction_Needs_Seek_Resource\""));
        assertFalse(tameworkExample.contains("\"NeedsSeekReachable\""));
        assertFalse(vanillaExample.contains("\"NeedsSeekReachable\""));
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String readResource(String path) {
        Path resourcePath = Path.of("src", "main", "resources", path);
        assertTrue(Files.exists(resourcePath), "Missing test resource file: " + resourcePath);
        try {
            String content = Files.readString(resourcePath, StandardCharsets.UTF_8);
            assertNotEquals("", content);
            return content;
        } catch (Exception ex) {
            throw new AssertionError("Failed to read resource: " + resourcePath, ex);
        }
    }

    private static int countOccurrences(String content, String literal) {
        return Pattern.compile(Pattern.quote(literal)).split(content, -1).length - 1;
    }
}
