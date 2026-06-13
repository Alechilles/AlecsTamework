package com.alechilles.alecstamework.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
        JsonObject relativeSpeed = parameters.getAsJsonObject("NeedsSeekRelativeSpeed");
        JsonObject moveTimeout = parameters.getAsJsonObject("NeedsSeekMoveTimeoutRange");
        assertNotNull(pathfinder, "Needs seek component must expose NeedsSeekUsePathfinder");
        assertNotNull(steering, "Needs seek component must expose NeedsSeekUseSteering");
        assertNotNull(bestPath, "Needs seek component must expose NeedsSeekUseBestPath");
        assertNotNull(reachable, "Needs seek component must expose NeedsSeekReachable");
        assertNotNull(relativeSpeed, "Needs seek component must expose NeedsSeekRelativeSpeed");
        assertNotNull(moveTimeout, "Needs seek component must expose NeedsSeekMoveTimeoutRange");
        assertTrue(pathfinder.get("Value").getAsBoolean(), "Needs seek must use pathfinding");
        assertFalse(steering.get("Value").getAsBoolean(), "Needs seek must not direct-steer around pathfinding");
        assertFalse(bestPath.get("Value").getAsBoolean(), "Needs seek must not accept partial best-path fallback");
        assertTrue(reachable.get("Value").getAsBoolean(), "Needs seek must require direct reachability at goal");
        assertTrue(relativeSpeed.get("Value").getAsDouble() >= 0.75,
                "Needs seek must move fast enough to avoid repeated timeout/retry loops");
        JsonArray timeoutRange = moveTimeout.getAsJsonArray("Value");
        assertTrue(timeoutRange.get(0).getAsDouble() >= 16.0,
                "Needs seek must allow enough time for valid preflight paths to complete");
        assertTrue(timeoutRange.get(1).getAsDouble() >= 20.0,
                "Needs seek must allow enough time for valid preflight paths to complete");
        assertTrue(content.contains("\"Compute\": \"NeedsSeekConsumeMaintainMaxDistance\""),
                "Seek motion must stay active until consume range");
        assertEquals(1, countOccurrences(content, "\"Type\": \"MaintainDistance\""),
                "Needs seek may only use maintain-distance after consume delay begins");
        assertEquals(4, countOccurrences(content, "\"Type\": \"TameworkNeedsResourceRejectTarget\""),
                "Needs seek movement failures must suppress the failed projected target");
        assertTrue(content.contains("\"Aborted\""),
                "Needs seek must exit when nav aborts an otherwise accepted path");
        assertTrue(content.contains("\"Blocked\""),
                "Needs seek must exit before full timeout when nav reports sustained blocking");
    }

    @Test
    void needsSeekSensorHonorsFailedCooldownBeforeEnteringMovement() {
        String content = readResource(
                "Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource_Sensor.json"
        );

        assertEquals(4, countOccurrences(content, "\"Compute\": \"NeedsSeekFailedCooldownName\""),
                "Priority and fallback planner branches must check the failed seek cooldown");
        assertEquals(4, countOccurrences(content, "\"State\": \"Stopped\""),
                "Priority and fallback planner branches must wait for the failed seek cooldown to stop");
    }

    @Test
    void needsSeekSensorPrioritizesLowerNeedBeforeFallbacks() {
        String content = readResource(
                "Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource_Sensor.json"
        );

        assertEquals(2, countOccurrences(content, "\"Type\": \"TameworkNeedLowest\""),
                "Needs planner must compare hunger and thirst before static fallback ordering");
        assertTrue(
                content.indexOf("\"Type\": \"TameworkNeedLowest\"")
                        < content.indexOf("Fallback water seek"),
                "Priority resource checks must run before fallback resource checks"
        );
    }

    @Test
    void needsSeekSensorLetsFoodWinNearTieBeforeWaterPriority() {
        String content = readResource(
                "Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource_Sensor.json"
        );

        assertTrue(content.contains("\"NeedsSeekFoodNearTieBiasRatio\""),
                "Needs planner must expose a small food bias for near-tie hunger/thirst cases");
        assertTrue(content.contains("\"AllowedHigherBy\""),
                "Food priority comparison must allow a small near-tie ratio band");
        assertTrue(
                content.indexOf("Prefer food seek when hunger is close to or below thirst")
                        < content.indexOf("Prefer water seek when thirst is the lowest active need"),
                "Near-tie food priority must run before strict water priority"
        );
    }

    @Test
    void needsSeekSensorStopsAfterAcceptingOneResourceTarget() {
        String content = readResource(
                "Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource_Sensor.json"
        );
        JsonArray instructions = object(content)
                .getAsJsonObject("Content")
                .getAsJsonArray("Instructions");

        assertFalse(
                findInstructionByParentState(instructions, "OnNeedsSeekWaterActive")
                        .get("Continue")
                        .getAsBoolean(),
                "Water target acceptance must not let food overwrite the shared needs-seek target"
        );
        assertFalse(
                findInstructionByParentState(instructions, "OnNeedsSeekFoodActive")
                        .get("Continue")
                        .getAsBoolean(),
                "Food target acceptance should stop later resource target branches"
        );
    }

    @Test
    void needsSeekMovementLogsStateTransitionsAndTerminalBranches() {
        String content = readResource("Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json");

        assertEquals(9, countOccurrences(content, "\"Type\": \"TameworkNeedsResourceMovementDiagnostic\""),
                "Needs seek movement diagnostics should cover transition and terminal movement branches only");
        assertTrue(content.contains("\"Stage\": \"move_start\""));
        assertTrue(content.contains("\"Stage\": \"target_lost\""));
        assertTrue(content.contains("\"Stage\": \"nav_defer\""));
        assertTrue(content.contains("\"Stage\": \"nav_blocked\""));
        assertTrue(content.contains("\"Stage\": \"move_timeout\""));
        assertTrue(content.contains("\"Stage\": \"consume_delay_start\""));
        assertTrue(content.contains("\"Stage\": \"consume_delay_timeout\""));
        assertTrue(content.contains("\"Detail\": \"strict_band\""));
        assertTrue(content.contains("\"Detail\": \"fallback_close_range\""));
    }

    @Test
    void examplesUseSharedNeedsSeekComponentDefaults() {
        String tameworkExample = readResource("Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json");
        String vanillaExample = readResource("Server/NPC/Roles/_Core/Templates/Template_Tamework_Example_Vanilla.json");
        String patchExample = readResource("Server/Tamework/Patches/Examples/Tamework_Example_Patch.json");

        assertTrue(tameworkExample.contains("\"Reference\": \"Component_Tamework_Instruction_Needs_Seek_Resource\""));
        assertTrue(vanillaExample.contains("\"Reference\": \"Component_Tamework_Instruction_Needs_Seek_Resource\""));
        assertFalse(tameworkExample.contains("\"NeedsSeekReachable\""));
        assertFalse(vanillaExample.contains("\"NeedsSeekReachable\""));
        assertPlannerRunsFromIdleAndHold(tameworkExample);
        assertPlannerRunsFromIdleAndHold(vanillaExample);
        assertPlannerRunsFromIdleAndHold(patchExample);
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

    private static JsonObject findInstructionByParentState(JsonArray instructions, String state) {
        for (JsonElement instructionElement : instructions) {
            JsonObject instruction = instructionElement.getAsJsonObject();
            JsonArray actions = instruction.getAsJsonArray("Actions");
            if (actions == null) {
                continue;
            }
            for (JsonElement actionElement : actions) {
                JsonObject action = actionElement.getAsJsonObject();
                if ("ParentState".equals(action.get("Type").getAsString())
                        && state.equals(action.get("State").getAsString())) {
                    return instruction;
                }
            }
        }
        throw new AssertionError("Missing needs-seek parent state action: " + state);
    }

    private static void assertPlannerRunsFromIdleAndHold(String content) {
        assertTrue(content.contains("\"Reference\": \"Component_Tamework_Instruction_Needs_Seek_Resource_Sensor\""));
        assertTrue(content.contains("\"Type\": \"Or\""));
        assertTrue(content.contains("\"State\": \"Idle\""));
        assertTrue(content.contains("\"State\": \"Hold\""));
    }
}
