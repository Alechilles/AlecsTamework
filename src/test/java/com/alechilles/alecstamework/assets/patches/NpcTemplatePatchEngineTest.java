package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class NpcTemplatePatchEngineTest {

    private final NpcTemplatePatchEngine engine = new NpcTemplatePatchEngine();

    @Test
    void appliesRawObjectAndArrayOperations() {
        JsonObject template = object("""
                {
                  "Role": {
                    "Parameters": {
                      "Speed": 1
                    }
                  },
                  "Instructions": [
                    { "$Comment": "Anchor" }
                  ],
                  "Obsolete": true
                }
                """);
        NpcTemplatePatchDefinition patch = patch("""
                {
                  "Id": "TestPatch",
                  "Target": "Server/NPC/Roles/_Core/Templates/Test.json",
                  "Operations": [
                    {
                      "Id": "merge-params",
                      "Op": "Merge",
                      "Path": "/Role/Parameters",
                      "Value": {
                        "TameworkConfig": "TwIntTest"
                      }
                    },
                    {
                      "Id": "add-flag",
                      "Op": "Add",
                      "Path": "/Role/Enabled",
                      "Value": true
                    },
                    {
                      "Id": "replace-speed",
                      "Op": "Replace",
                      "Path": "/Role/Parameters/Speed",
                      "Value": 2
                    },
                    {
                      "Id": "insert-instruction",
                      "Op": "Insert",
                      "Path": "/Instructions",
                      "Position": "After",
                      "Find": { "$Comment": "Anchor" },
                      "Value": { "Component": "Component_Tamework_Instruction_Follow" }
                    },
                    {
                      "Id": "remove-obsolete",
                      "Op": "Remove",
                      "Path": "/Obsolete"
                    }
                  ]
                }
                """);

        NpcTemplatePatchEngine.PatchResult result = engine.apply(template, List.of(patch));

        assertEquals(2, result.patched().getAsJsonObject("Role").getAsJsonObject("Parameters").get("Speed").getAsInt());
        assertEquals(
                "TwIntTest",
                result.patched().getAsJsonObject("Role").getAsJsonObject("Parameters").get("TameworkConfig").getAsString()
        );
        assertTrue(result.patched().getAsJsonObject("Role").get("Enabled").getAsBoolean());
        assertFalse(result.patched().has("Obsolete"));
        assertEquals(
                "Component_Tamework_Instruction_Follow",
                result.patched().getAsJsonArray("Instructions").get(1).getAsJsonObject().get("Component").getAsString()
        );
        assertEquals(5, result.status().getApplied().size());
    }

    @Test
    void requiredMissingAnchorFailsPatch() {
        JsonObject template = object("""
                {
                  "Instructions": []
                }
                """);
        NpcTemplatePatchDefinition patch = patch("""
                {
                  "Id": "BrokenPatch",
                  "Target": "Server/NPC/Roles/_Core/Templates/Test.json",
                  "Operations": [
                    {
                      "Id": "missing-anchor",
                      "Op": "Insert",
                      "Path": "/Instructions",
                      "Position": "After",
                      "Find": { "$Comment": "Missing" },
                      "Value": { "Component": "Component_Tamework_Instruction_Follow" }
                    }
                  ]
                }
                """);

        assertThrows(NpcTemplatePatchEngine.PatchFailureException.class, () -> engine.apply(template, List.of(patch)));
    }

    @Test
    void optionalMissingAnchorIsSkipped() {
        JsonObject template = object("""
                {
                  "Instructions": []
                }
                """);
        NpcTemplatePatchDefinition patch = patch("""
                {
                  "Id": "OptionalPatch",
                  "Target": "Server/NPC/Roles/_Core/Templates/Test.json",
                  "Operations": [
                    {
                      "Id": "missing-anchor",
                      "Op": "Insert",
                      "Path": "/Instructions",
                      "Position": "After",
                      "Required": false,
                      "Find": { "$Comment": "Missing" },
                      "Value": { "Component": "Component_Tamework_Instruction_Follow" }
                    }
                  ]
                }
                """);

        NpcTemplatePatchEngine.PatchResult result = engine.apply(template, List.of(patch));

        assertEquals(0, result.patched().getAsJsonArray("Instructions").size());
        assertEquals(1, result.status().getSkipped().size());
    }

    @Test
    void insertExistingMatcherMakesReloadIdempotent() {
        JsonObject template = object("""
                {
                  "Instructions": [
                    { "$Comment": "Anchor" },
                    { "Component": "Component_Tamework_Instruction_Follow" }
                  ]
                }
                """);
        NpcTemplatePatchDefinition patch = patch("""
                {
                  "Id": "IdempotentPatch",
                  "Target": "Server/NPC/Roles/_Core/Templates/Test.json",
                  "Operations": [
                    {
                      "Id": "follow",
                      "Op": "Insert",
                      "Path": "/Instructions",
                      "Position": "After",
                      "Find": { "$Comment": "Anchor" },
                      "Existing": { "Component": "Component_Tamework_Instruction_Follow" },
                      "Value": { "Component": "Component_Tamework_Instruction_Follow" }
                    }
                  ]
                }
                """);

        NpcTemplatePatchEngine.PatchResult result = engine.apply(template, List.of(patch));

        assertEquals(2, result.patched().getAsJsonArray("Instructions").size());
        assertEquals(1, result.status().getSkipped().size());
    }

    @Test
    void ahStyleMacrosPatchDistinctTemplateRegions() {
        JsonObject template = object("""
                {
                  "StateInstructions": [
                    { "$Comment": "Tamework state anchor" }
                  ],
                  "HookInstructions": [
                    { "$Comment": "Tamework hook anchor" }
                  ],
                  "InteractionInstruction": {
                    "Instructions": [
                      { "$Comment": "Vanilla interaction anchor" }
                    ]
                  }
                }
                """);
        NpcTemplatePatchDefinition patch = patch("""
                {
                  "Id": "AHStyleLivestockPatch",
                  "Target": "Server/NPC/Roles/_Core/Templates/AH_Template_Livestock.json",
                  "Operations": [
                    {
                      "Id": "follow-state",
                      "Op": "Macro",
                      "Macro": "TameworkStateInstruction",
                      "Path": "/StateInstructions",
                      "Position": "After",
                      "Find": { "$Comment": "Tamework state anchor" },
                      "Options": {
                        "Component": "Component_Tamework_Instruction_Follow_Simple_TP",
                        "Enabled": { "Compute": "CanFollow" }
                      }
                    },
                    {
                      "Id": "pet-hook",
                      "Op": "Macro",
                      "Macro": "TameworkHookInstruction",
                      "Path": "/HookInstructions",
                      "Position": "After",
                      "Find": { "$Comment": "Tamework hook anchor" },
                      "Options": {
                        "HookId": "TwHook_Pet",
                        "Consume": true,
                        "Instructions": [
                          { "Component": "Component_AH_PetEffects" }
                        ]
                      }
                    },
                    {
                      "Id": "interaction",
                      "Op": "Macro",
                      "Macro": "TameworkInteractionBridge",
                      "Path": "/InteractionInstruction/Instructions",
                      "Position": "After",
                      "Find": { "$Comment": "Vanilla interaction anchor" },
                      "Options": {
                        "ActionFields": {
                          "LovedItems": { "Compute": "AttractiveItemSet" },
                          "IsHarvestable": { "Compute": "IsHarvestable" },
                          "IsMountable": { "Compute": "IsMountable" },
                          "HarvestInteractionContext": { "Compute": "HarvestInteractionContext" }
                        }
                      }
                    }
                  ]
                }
                """);

        NpcTemplatePatchEngine.PatchResult result = engine.apply(template, List.of(patch));

        assertEquals(
                "Component_Tamework_Instruction_Follow_Simple_TP",
                result.patched().getAsJsonArray("StateInstructions").get(1).getAsJsonObject()
                        .getAsJsonArray("Instructions").get(0).getAsJsonObject().get("Component").getAsString()
        );
        assertEquals(
                "TameworkHook",
                result.patched().getAsJsonArray("HookInstructions").get(1).getAsJsonObject()
                        .getAsJsonObject("Sensor").get("Type").getAsString()
        );
        assertEquals(
                "TameworkInteractPrompt",
                result.patched().getAsJsonObject("InteractionInstruction").getAsJsonArray("Instructions")
                        .get(1).getAsJsonObject().getAsJsonArray("Actions").get(0).getAsJsonObject()
                        .get("Type").getAsString()
        );
        assertEquals(
                "TameworkInteract",
                result.patched().getAsJsonObject("InteractionInstruction").getAsJsonArray("Instructions")
                        .get(2).getAsJsonObject().getAsJsonArray("Actions").get(2).getAsJsonObject()
                        .get("Type").getAsString()
        );
        assertEquals(4, result.status().getApplied().size());
    }

    @Test
    void bundledPatchExampleUpgradesBareTemplateWithTameworkBehavior() throws Exception {
        JsonObject role = object(readResource(
                "Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example_Patch.json"
        ));
        assertEquals("Tamework_Example_Patch", role.get("Reference").getAsString());

        JsonObject template = object(readResource(
                "Server/NPC/Roles/_Core/Templates/Tamework_Example_Patch.json"
        ));
        JsonObject templateParameters = template.getAsJsonObject("Parameters");
        for (String modifyKey : role.getAsJsonObject("Modify").keySet()) {
            assertTrue(templateParameters.has(modifyKey), "Role modifies missing template parameter " + modifyKey);
        }

        String baseJson = template.toString();
        assertFalse(baseJson.contains("TameworkInteract"));
        assertFalse(baseJson.contains("Component_Tamework"));
        assertFalse(baseJson.contains("\"Sleep\""));

        NpcTemplatePatchDefinition patch = NpcTemplatePatchDefinition.parse(
                object(readResource("Server/Tamework/Patches/Examples/Tamework_Example_Patch.json")),
                "TestPack",
                "Server/Tamework/Patches/Examples/Tamework_Example_Patch.json"
        );
        NpcTemplatePatchEngine.PatchResult result = engine.apply(template, List.of(patch));
        String patchedJson = result.patched().toString();

        assertTrue(patchedJson.contains("TameworkInteractPrompt"));
        assertTrue(patchedJson.contains("TameworkInteract"));
        assertTrue(patchedJson.contains("Component_Tamework_Instruction_Command_Move"));
        assertTrue(patchedJson.contains("Component_Tamework_Instruction_Follow_Simple_TP"));
        assertTrue(patchedJson.contains("Component_Tamework_Instruction_Hold"));
        assertTrue(patchedJson.contains("Component_Tamework_Instruction_Defend"));
        assertTrue(patchedJson.contains("Component_Tamework_Instruction_Needs_Seek_Resource"));
        assertTrue(patchedJson.contains("Component_Tamework_Instruction_Breeding_Pair"));
        assertTrue(patchedJson.contains("Tamework patch: validation-only state setters"));
        assertTrue(patchedJson.contains("Tamework patch: sleep transitions"));
        assertTrue(patchedJson.contains("Tamework patch: sleep state"));
        for (JsonElement transition : result.patched().getAsJsonArray("StateTransitions")) {
            JsonObject transitionObject = transition.getAsJsonObject();
            assertTrue(transitionObject.has("States"), "StateTransition is missing States: " + transitionObject);
            assertTrue(transitionObject.has("Actions"), "StateTransition is missing Actions: " + transitionObject);
        }
        assertEquals(17, result.status().getApplied().size());
        assertEquals(0, result.status().getFailed().size());
    }

    private static NpcTemplatePatchDefinition patch(String json) {
        return NpcTemplatePatchDefinition.parse(object(json), "TestPack", "Server/Tamework/Patches/Test.json");
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String readResource(String path) throws Exception {
        ClassLoader loader = NpcTemplatePatchEngineTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            assertNotNull(stream, "Missing test resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
