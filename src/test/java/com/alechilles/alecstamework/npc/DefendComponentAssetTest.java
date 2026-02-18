package com.alechilles.alecstamework.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests defend asset wiring for owner-aware threat detection and command overrides. */
class DefendComponentAssetTest {

    @Test
    void defendInstructionReferencesHostileToMasterTargetSensor() {
        String defend = readResource("Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Defend.json");
        assertTrue(defend.contains("\"Reference\": \"Component_Tamework_Sensor_Defend_Hostile_To_MasterTarget\""));
    }

    @Test
    void defendInstructionNoLongerRequiresAttitudeFilterForCombatAttack() {
        String defend = readResource("Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Defend.json");
        assertFalse(defend.contains("\"Type\": \"Attitude\""));
    }

    @Test
    void defendInstructionUsesConfigurableFollowMacroElement() {
        String defend = readResource("Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Defend.json");
        assertTrue(defend.contains("\"DefendFollowMacroElement\""));
        assertTrue(defend.contains("\"Value\": \"Component_Tamework_Instruction_Follow_Simple_TP\""));
        assertTrue(defend.contains("\"Compute\": \"DefendFollowMacroElement\""));
        JsonObject root = JsonParser.parseString(defend).getAsJsonObject();
        JsonObject content = root.getAsJsonObject("Content");
        assertNotNull(content, "Defend content must exist");
        JsonArray instructions = content.getAsJsonArray("Instructions");
        assertNotNull(instructions, "Defend content instructions must exist");
        JsonObject followReferenceNode = findFollowMacroReferenceNode(instructions);
        assertNotNull(followReferenceNode, "Defend follow macro reference node must exist");
        assertTrue(followReferenceNode.has("Interfaces"), "Defend follow macro reference must declare Interfaces");
        JsonArray interfaces = followReferenceNode.getAsJsonArray("Interfaces");
        assertEquals(2, interfaces.size(), "Defend follow macro interfaces must include exactly two entries");
        assertEquals("Hytale.Instruction.Null", interfaces.get(0).getAsString());
        assertEquals("Tamework.Instruction.Follow", interfaces.get(1).getAsString());
    }

    @Test
    void tameworkFollowComponentsDeclareFollowInterface() {
        assertComponentInterface(
            "Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Follow_Simple_TP.json",
            "Tamework.Instruction.Follow");
        assertComponentInterface(
            "Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Follow_Simple.json",
            "Tamework.Instruction.Follow");
        assertComponentInterface(
            "Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Follow_Advanced.json",
            "Tamework.Instruction.Follow");
    }

    @Test
    void defendInstructionNodesDoNotMixActionsAndInstructions() {
        String defend = readResource("Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Defend.json");
        JsonObject root = JsonParser.parseString(defend).getAsJsonObject();
        JsonObject content = root.getAsJsonObject("Content");
        assertNotNull(content, "Defend content must exist");
        JsonArray instructions = content.getAsJsonArray("Instructions");
        assertNotNull(instructions, "Defend content instructions must exist");
        validateInstructionNodes(instructions, "Content.Instructions");
    }

    @Test
    void hostileToMasterTargetSensorUsesCustomAttitudeFilter() {
        String sensor = readResource("Server/NPC/Roles/_Core/Components/Component_Tamework_Sensor_Defend_Hostile_To_MasterTarget.json");
        assertTrue(sensor.contains("\"Type\": \"TameworkAttitudeFromTargetSlot\""));
        assertTrue(sensor.contains("\"SourceTargetSlot\": {"));
        assertTrue(sensor.contains("\"Value\": \"MasterTarget\""));
    }

    private String readResource(String path) {
        Path resourcePath = Path.of("src", "main", "resources", path);
        assertNotNull(resourcePath, "Missing test resource path object: " + path);
        assertTrue(Files.exists(resourcePath), "Missing test resource file: " + resourcePath);
        try {
            String content = Files.readString(resourcePath, StandardCharsets.UTF_8);
            assertNotEquals("", content);
            return content;
        } catch (Exception e) {
            throw new RuntimeException("Failed reading resource: " + path, e);
        }
    }

    private void validateInstructionNodes(JsonArray instructions, String path) {
        for (int i = 0; i < instructions.size(); i++) {
            JsonObject node = instructions.get(i).getAsJsonObject();
            boolean hasActions = node.has("Actions");
            boolean hasInstructions = node.has("Instructions");
            assertFalse(
                hasActions && hasInstructions,
                "Instruction node has both Actions and Instructions at " + path + "[" + i + "]");

            if (hasInstructions) {
                validateInstructionNodes(node.getAsJsonArray("Instructions"), path + "[" + i + "].Instructions");
            }
        }
    }

    private JsonObject findFollowMacroReferenceNode(JsonArray instructions) {
        for (int i = 0; i < instructions.size(); i++) {
            JsonObject node = instructions.get(i).getAsJsonObject();
            if (node.has("Reference")) {
                JsonObject reference = node.getAsJsonObject("Reference");
                if (reference.has("Compute")
                    && "DefendFollowMacroElement".equals(reference.get("Compute").getAsString())) {
                    return node;
                }
            }

            if (node.has("Instructions")) {
                JsonObject nested = findFollowMacroReferenceNode(node.getAsJsonArray("Instructions"));
                if (nested != null) {
                    return nested;
                }
            }
        }

        return null;
    }

    private void assertComponentInterface(String path, String expectedInterface) {
        String component = readResource(path);
        JsonObject root = JsonParser.parseString(component).getAsJsonObject();
        assertTrue(root.has("Interface"), "Component must declare Interface: " + path);
        assertEquals(expectedInterface, root.get("Interface").getAsString(), "Unexpected interface in: " + path);
    }
}
