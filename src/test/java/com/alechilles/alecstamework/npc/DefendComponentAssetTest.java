package com.alechilles.alecstamework.npc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
