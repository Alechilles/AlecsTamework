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
import java.util.LinkedHashSet;
import java.util.Set;
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
        JsonObject stallGraceSeconds = parameters.getAsJsonObject("NeedsSeekStallGraceSeconds");
        JsonObject stallMinProgress = parameters.getAsJsonObject("NeedsSeekStallMinProgress");
        JsonObject consumeStartDistance = parameters.getAsJsonObject("NeedsSeekConsumeStartDistance");
        assertNotNull(pathfinder, "Needs seek component must expose NeedsSeekUsePathfinder");
        assertNotNull(steering, "Needs seek component must expose NeedsSeekUseSteering");
        assertNotNull(bestPath, "Needs seek component must expose NeedsSeekUseBestPath");
        assertNotNull(reachable, "Needs seek component must expose NeedsSeekReachable");
        assertNotNull(relativeSpeed, "Needs seek component must expose NeedsSeekRelativeSpeed");
        assertNotNull(moveTimeout, "Needs seek component must expose NeedsSeekMoveTimeoutRange");
        assertNotNull(stallGraceSeconds, "Needs seek component must expose NeedsSeekStallGraceSeconds");
        assertNotNull(stallMinProgress, "Needs seek component must expose NeedsSeekStallMinProgress");
        assertNotNull(consumeStartDistance, "Needs seek component must expose NeedsSeekConsumeStartDistance");
        assertTrue(pathfinder.get("Value").getAsBoolean(), "Needs seek must use pathfinding");
        assertTrue(steering.get("Value").getAsBoolean(),
                "Needs seek experiment should allow vanilla simple steering while pathfinding remains enabled");
        assertFalse(bestPath.get("Value").getAsBoolean(), "Needs seek must not accept partial best-path fallback");
        assertFalse(reachable.get("Value").getAsBoolean(),
                "Needs seek should rely on preflight reachability instead of the motion exact-target gate");
        assertTrue(relativeSpeed.get("Value").getAsDouble() >= 0.75,
                "Needs seek must move fast enough to avoid repeated timeout/retry loops");
        JsonArray timeoutRange = moveTimeout.getAsJsonArray("Value");
        assertTrue(timeoutRange.get(0).getAsDouble() >= 28.0,
                "Needs seek must allow enough time for valid preflight paths to complete");
        assertTrue(timeoutRange.get(1).getAsDouble() >= 36.0,
                "Needs seek must allow enough time for valid preflight paths to complete");
        assertEquals(2.25, consumeStartDistance.get("Value").getAsDouble(), 0.0001,
                "Needs seek must allow a small arrival buffer before consume delay starts");
        assertTrue(content.contains("\"Type\": \"Seek\""),
                "Needs seek should use vanilla Seek while testing simple steering with pathfinding");
        assertTrue(content.contains("\"UseSteering\": {\n                \"Compute\": \"NeedsSeekUseSteering\"")
                        || content.contains("\"UseSteering\": {\r\n                \"Compute\": \"NeedsSeekUseSteering\""),
                "Needs seek must pass the simple steering toggle into vanilla Seek");
        assertTrue(content.contains("\"StopDistance\": {\n                \"Compute\": \"NeedsSeekConsumeStartDistance\"")
                        || content.contains("\"StopDistance\": {\r\n                \"Compute\": \"NeedsSeekConsumeStartDistance\""),
                "Seek motion must stop at the relaxed consume-start range to avoid near-arrival timeout loops");
        assertTrue(content.contains("\"Stage\": \"consume_delay_start\"")
                        && content.contains("\"Compute\": \"NeedsSeekConsumeStartDistance\""),
                "Consume delay must be allowed to start at the relaxed arrival distance");
        assertEquals(1, countOccurrences(content, "\"Type\": \"MaintainDistance\""),
                "Needs seek may only use maintain-distance after consume delay begins");
        assertEquals(5, countOccurrences(content, "\"Type\": \"TameworkNeedsResourceRejectTarget\""),
                "Needs seek movement failures must suppress the failed projected target");
        assertTrue(content.contains("\"Aborted\""),
                "Needs seek must exit when nav aborts an otherwise accepted path");
        assertTrue(content.contains("\"Blocked\""),
                "Needs seek must exit before full timeout when nav reports sustained blocking");
        assertTrue(content.contains("\"Type\": \"TameworkNeedsResourceMovementStalled\""),
                "Needs seek must exit before full timeout when movement makes no useful progress");
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
    void needsSeekMovementLocksPlannerTargetOnceActive() {
        String content = readResource("Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json");
        JsonObject component = object(content);
        JsonObject parameters = component.getAsJsonObject("Parameters");

        assertNotNull(parameters.getAsJsonObject("NeedsSeekActiveTargetSlot"),
                "Needs seek movement must expose a locked active target slot");
        assertEquals(2, countOccurrences(content, "\"Compute\": \"NeedsSeekTargetSlot\""),
                "Only default recovery and move startup may read the planner target slot directly");
        assertEquals(1, countOccurrences(content, "\"Type\": \"StorePosition\""),
                "Needs seek must copy the planner target into the active movement slot exactly once");
        assertTrue(countOccurrences(content, "\"Compute\": \"NeedsSeekActiveTargetSlot\"") > 10,
                "Runtime movement, consume, and failure branches must use the locked active target slot");
        assertEquals(8, countOccurrences(content, "\"Type\": \"ReleaseTarget\""),
                "Every terminal movement branch must clear the locked active target");
    }

    @Test
    void needsSeekMovementLogsStateTransitionsAndTerminalBranches() {
        String content = readResource("Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json");

        assertEquals(13, countOccurrences(content, "\"Type\": \"TameworkNeedsResourceMovementDiagnostic\""),
                "Needs seek movement diagnostics should cover transition, active probe, and terminal movement branches");
        assertTrue(content.contains("\"Stage\": \"move_start\""));
        assertTrue(content.contains("\"Stage\": \"move_probe\""));
        assertTrue(content.contains("\"Stage\": \"target_lost\""));
        assertTrue(content.contains("\"Stage\": \"nav_defer\""));
        assertTrue(content.contains("\"Stage\": \"nav_blocked\""));
        assertTrue(content.contains("\"Stage\": \"move_stalled\""));
        assertTrue(content.contains("\"Stage\": \"move_timeout\""));
        assertTrue(content.contains("\"Stage\": \"consume_delay_start\""));
        assertTrue(content.contains("\"Stage\": \"consume_delay_timeout\""));
        assertTrue(content.contains("\"Detail\": \"strict_band\""));
        assertTrue(content.contains("\"Detail\": \"fallback_close_range\""));
        assertTrue(content.contains("\"Stage\": \"consume_repeat\""));
    }

    @Test
    void needsSeekConsumptionRepeatsUntilNeedThresholdIsSatisfied() {
        String content = readResource("Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json");

        assertTrue(content.contains("\"State\": \".PostConsume\""),
                "Needs seek must inspect the need after each consume before completing");
        assertEquals(2, countOccurrences(content, "\"Type\": \"TameworkNeedsResourceConsume\""),
                "Strict and fallback close-range branches should remain the only consume entry points");
        assertEquals(2, countOccurrences(content, "\"ReleaseTarget\": false"),
                "Consume attempts must keep the active reservation while repeat checks run");
        assertEquals(2, countOccurrences(content, "\"Type\": \"TameworkNeedsResourceConsumeSucceeded\""),
                "Food and water repeat branches must only loop after a successful consume attempt");
        assertEquals(1, countOccurrences(content, "\"Type\": \"TameworkNeedsResourceReleaseTarget\""),
                "Successful post-consume completion must release Tamework's internal approach-point reservation");
        assertTrue(content.contains("\"Need\": \"Hunger\""));
        assertTrue(content.contains("\"Need\": \"Thirst\""));
        assertTrue(content.contains("\"Compute\": \"NeedsSeekWhenHungerBelowRatio\""));
        assertTrue(content.contains("\"Compute\": \"NeedsSeekWhenThirstBelowRatio\""));
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
        assertPlannerRunsFromPassiveCompanionStates(tameworkExample);
        assertPlannerRunsFromPassiveCompanionStates(vanillaExample);
        assertPlannerRunsFromPassiveCompanionStates(patchExample);
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

    private static void assertPlannerRunsFromPassiveCompanionStates(String content) {
        JsonObject bridge = findNeedsSensorBridge(object(content));
        JsonObject sensor = bridge.getAsJsonObject("Sensor");
        assertNotNull(sensor, "Needs seek bridge must have a state gate sensor");
        assertEquals("Or", sensor.get("Type").getAsString());

        Set<String> states = new LinkedHashSet<>();
        for (JsonElement sensorElement : sensor.getAsJsonArray("Sensors")) {
            JsonObject stateSensor = sensorElement.getAsJsonObject();
            assertEquals("State", stateSensor.get("Type").getAsString());
            states.add(stateSensor.get("State").getAsString());
        }
        assertEquals(Set.of("Idle", "Hold", "Follow", "Sleep"), states,
                "Needs scanner must stay active in passive companion states before switching to movement");
    }

    private static JsonObject findNeedsSensorBridge(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new AssertionError("Missing needs-seek sensor bridge");
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonArray instructions = object.getAsJsonArray("Instructions");
            if (instructions != null && containsNeedsSensorReference(instructions)) {
                return object;
            }
            for (String key : object.keySet()) {
                try {
                    return findNeedsSensorBridge(object.get(key));
                } catch (AssertionError ignored) {
                    // Continue recursive search.
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                try {
                    return findNeedsSensorBridge(child);
                } catch (AssertionError ignored) {
                    // Continue recursive search.
                }
            }
        }
        throw new AssertionError("Missing needs-seek sensor bridge");
    }

    private static boolean containsNeedsSensorReference(JsonArray instructions) {
        for (JsonElement instructionElement : instructions) {
            JsonObject instruction = instructionElement.getAsJsonObject();
            JsonElement reference = instruction.get("Reference");
            if (reference != null
                    && "Component_Tamework_Instruction_Needs_Seek_Resource_Sensor".equals(reference.getAsString())) {
                return true;
            }
        }
        return false;
    }
}
