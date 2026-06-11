package com.alechilles.alecstamework.npc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Tests asset wiring for needs-seek movement behavior. */
class NeedsSeekComponentAssetTest {

    @Test
    void needsSeekRequiresReachableProjectedStandTarget() {
        JsonObject component = object(readResource(
                "Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json"
        ));

        JsonObject parameters = component.getAsJsonObject("Parameters");
        assertNotNull(parameters, "Needs seek component must declare parameters");
        JsonObject reachable = parameters.getAsJsonObject("NeedsSeekReachable");
        assertNotNull(reachable, "Needs seek component must expose NeedsSeekReachable");
        assertTrue(reachable.get("Value").getAsBoolean(), "Needs seek must require direct reachability at goal");
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
}
