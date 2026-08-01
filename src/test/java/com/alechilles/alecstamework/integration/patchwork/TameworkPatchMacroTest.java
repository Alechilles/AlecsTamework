package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Locks the embedded macro JSON to the legacy Tamework macro expansion contract. */
class TameworkPatchMacroTest {
    @Test
    void interactionBridgeEmitsLegacyPromptAndInteractionOperationsWithoutMutatingInput() {
        JsonObject operation = object("""
                { "Id":"interact", "Path":"/Branches", "Position":"After", "Required":false,
                  "Find":{"$Comment":"anchor"}, "Existing":{"old":true},
                  "Options":{"ActionFields":{"Flag":{"Compute":"flag"}}} }
                """);
        JsonObject original = operation.deepCopy();

        JsonArray expanded = new TameworkInteractionBridgeMacro().expand(operation);

        assertJsonEquals("""
                [
                  {"Id":"interact.prompt","Op":"Insert","Path":"/Branches","Position":"After","Required":false,
                   "Value":{"Enabled":{"Compute":"true"},"Sensor":{"Type":"Any"},"Actions":[{"Flag":{"Compute":"flag"},"Type":"TameworkInteractPrompt"}],"Continue":true},
                   "Find":{"$Comment":"anchor"},"Existing":{"Actions":{"$Contains":{"Type":"TameworkInteractPrompt"}}}},
                  {"Id":"interact.interact","Op":"Insert","Path":"/Branches","Position":"End","Required":false,
                   "Value":{"Enabled":{"Compute":"true"},"Sensor":{"Type":"HasInteracted"},"Actions":[{"Type":"LockOnInteractionTarget","TargetSlot":"InteractionTarget"},{"Type":"LockOnInteractionTarget","TargetSlot":"MasterTarget"},{"Flag":{"Compute":"flag"},"Type":"TameworkInteract"}]},
                   "Existing":{"Actions":{"$Contains":{"Type":"TameworkInteract"}}}}
                ]
                """, expanded);
        assertEquals(original, operation);
        assertEquals("ActionFields must be an object.", assertThrows(IllegalArgumentException.class,
                () -> new TameworkInteractionBridgeMacro().expand(object(
                        "{\"Id\":\"interact\",\"Options\":{\"ActionFields\":[]}}"
                ))).getMessage());
        assertEquals("Options must be an object.", assertThrows(IllegalArgumentException.class,
                () -> new TameworkInteractionBridgeMacro().expand(object(
                        "{\"Id\":\"interact\",\"Options\":[]}"
                ))).getMessage());
    }

    @Test
    void hookInstructionPreservesDefaultsAndRejectsMalformedOptions() {
        JsonObject operation = object("""
                {"Id":"hook","Path":"/Hooks","Position":"Before","Required":true,"Find":{"id":"anchor"},
                 "Options":{"HookId":"pet","Instructions":[{"Type":"Signal"}]}}
                """);
        JsonObject original = operation.deepCopy();

        assertJsonEquals("""
                [{"Id":"hook.hook","Op":"Insert","Path":"/Hooks","Position":"Before","Required":true,
                  "Value":{"Enabled":{"Compute":"true"},"Continue":true,"Sensor":{"Type":"TameworkHook","HookId":"pet","Consume":true},"Instructions":[{"Type":"Signal"}]},
                  "Find":{"id":"anchor"},"Existing":{"Sensor":{"Type":"TameworkHook","HookId":"pet"}}}]
                """, new TameworkHookInstructionMacro().expand(operation));
        assertEquals(original, operation);
        assertEquals("hook requires option HookId.", assertThrows(IllegalArgumentException.class,
                () -> new TameworkHookInstructionMacro().expand(object("{\"Id\":\"hook\",\"Options\":{}}"))).getMessage());
        assertEquals("Consume must be a boolean.", assertThrows(IllegalArgumentException.class,
                () -> new TameworkHookInstructionMacro().expand(object("{\"Id\":\"hook\",\"Options\":{\"HookId\":\"pet\",\"Consume\":\"yes\"}}"))).getMessage());
        assertEquals("Options must be an object.", assertThrows(IllegalArgumentException.class,
                () -> new TameworkHookInstructionMacro().expand(object("{\"Id\":\"hook\",\"Options\":[]}"))).getMessage());
    }

    @Test
    void stateInstructionCopiesOptionalObjectsAndRejectsMissingComponentWithoutMutatingInput() {
        JsonObject operation = object("""
                {"Id":"state","Path":"/States","Position":"After","Options":
                 {"Component":"Follow","Enabled":{"Compute":"enabled"},"Sensor":{"Type":"Near"}}}
                """);
        JsonObject original = operation.deepCopy();

        assertJsonEquals("""
                [{"Id":"state.state","Op":"Insert","Path":"/States","Position":"After","Required":true,
                  "Value":{"Enabled":{"Compute":"enabled"},"Continue":true,"Instructions":[{"Component":"Follow"}],"Sensor":{"Type":"Near"}},
                  "Existing":{"Instructions":{"$Contains":{"Component":"Follow"}}}}]
                """, new TameworkStateInstructionMacro().expand(operation));
        assertEquals(original, operation);
        assertEquals("state requires option Component.", assertThrows(IllegalArgumentException.class,
                () -> new TameworkStateInstructionMacro().expand(object("{\"Id\":\"state\",\"Options\":{}}"))).getMessage());
        assertEquals("Options must be an object.", assertThrows(IllegalArgumentException.class,
                () -> new TameworkStateInstructionMacro().expand(object(
                        "{\"Id\":\"state\",\"Options\":[]}"
                ))).getMessage());
    }

    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static void assertJsonEquals(String expected, JsonArray actual) {
        assertEquals(JsonParser.parseString(expected), actual);
    }
}
