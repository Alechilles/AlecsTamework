package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class AssetPatchEngineTest {

    private final AssetPatchEngine engine = new AssetPatchEngine();

    @Test
    void appliesExpandedMultiTargetDefinitionsIndependently() {
        JsonObject cowTemplate = object("""
                {
                  "Name": "Cow"
                }
                """);
        JsonObject sheepTemplate = object("""
                {
                  "Name": "Sheep"
                }
                """);
        List<AssetPatchDefinition> definitions = AssetPatchDefinition.parseAll(object("""
                {
                  "Id": "SharedLivestockPatch",
                  "Targets": [
                    "Server/NPC/Roles/_Core/Templates/Cow.json",
                    "Server/NPC/Roles/_Core/Templates/Sheep.json"
                  ],
                  "Operations": [
                    {
                      "Id": "flag",
                      "Op": "Add",
                      "Path": "/Patched",
                      "Value": true
                    }
                  ]
                }
                """), "TestPack", "Server/Tamework/Patches/Shared.json");

        AssetPatchEngine.PatchResult cowResult = engine.apply(cowTemplate, List.of(definitions.get(0)));
        AssetPatchEngine.PatchResult sheepResult = engine.apply(sheepTemplate, List.of(definitions.get(1)));

        assertTrue(cowResult.patched().get("Patched").getAsBoolean());
        assertTrue(sheepResult.patched().get("Patched").getAsBoolean());
        assertEquals("Server/NPC/Roles/_Core/Templates/Cow.json", definitions.get(0).getTarget());
        assertEquals("Server/NPC/Roles/_Core/Templates/Sheep.json", definitions.get(1).getTarget());
        assertEquals(1, cowResult.status().getApplied().size());
        assertEquals(1, sheepResult.status().getApplied().size());
    }

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
        AssetPatchDefinition patch = patch("""
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

        AssetPatchEngine.PatchResult result = engine.apply(template, List.of(patch));

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
    void patchesVanillaSafeItemAssetWithTameworkCommandAction() {
        JsonObject item = object("""
                {
                  "Id": "VanillaSafeCommandWhistle",
                  "DisplayName": "Command Whistle",
                  "RootItemInteraction": {
                    "Actions": [
                      { "Type": "Inspect" }
                    ]
                  }
                }
                """);
        assertFalse(item.toString().contains("TameworkCommand"));
        AssetPatchDefinition patch = patch("""
                {
                  "Id": "CommandItemActionPatch",
                  "Target": "Server/Item/Items/Commands/Command_Whistle.json",
                  "Operations": [
                    {
                      "Id": "command-action",
                      "Op": "Insert",
                      "Path": "/RootItemInteraction/Actions",
                      "Position": "End",
                      "Existing": { "Type": "TameworkCommand" },
                      "Value": {
                        "Type": "TameworkCommand",
                        "ConfigId": "TwCommandItem_CommandWhistle"
                      }
                    }
                  ]
                }
                """);

        AssetPatchEngine.PatchResult result = engine.apply(item, List.of(patch));

        JsonObject commandAction = result.patched().getAsJsonObject("RootItemInteraction")
                .getAsJsonArray("Actions")
                .get(1)
                .getAsJsonObject();
        assertEquals("TameworkCommand", commandAction.get("Type").getAsString());
        assertEquals("TwCommandItem_CommandWhistle", commandAction.get("ConfigId").getAsString());
        assertEquals(1, result.status().getApplied().size());
    }

    @Test
    void bundledBucketTroughIntegrationUsesPatchesInsteadOfBucketOverrides() throws Exception {
        assertFalse(
                Files.exists(Path.of("src/main/resources/Server/Item/Items/Container/Container_Bucket.json")),
                "Tamework should not ship a full Container_Bucket item override."
        );
        assertFalse(
                Files.exists(Path.of("src/main/resources/Server/Item/Items/Deco/Deco_Bucket.json")),
                "Tamework should not ship a full Deco_Bucket item override."
        );

        assertBucketPatch(
                "Server/Tamework/Patches/Items/Tamework_Container_Bucket_Water_Trough_Patch.json",
                "Server/Item/Items/Container/Container_Bucket.json",
                "Container_Bucket"
        );
        assertBucketPatch(
                "Server/Tamework/Patches/Items/Tamework_Deco_Bucket_Water_Trough_Patch.json",
                "Server/Item/Items/Deco/Deco_Bucket.json",
                "Deco_Bucket"
        );
        assertEquals(
                "TargetExists",
                AssetPatchCondition.parseOptional(object(readResource(
                        "Server/Tamework/Patches/Items/Tamework_AlanDeco_Stackable_Bucket_Water_Trough_Patch.json"
                ))).describe()
        );
        assertBucketPatch(
                "Server/Tamework/Patches/Items/Tamework_AlanDeco_Stackable_Bucket_Water_Trough_Patch.json",
                "Server/Item/Items/Container/Container_Bucket_Stackable.json",
                "Container_Bucket"
        );
    }

    @Test
    void patchesTameworkConfigAssets() {
        JsonObject config = object("""
                {
                  "Id": "TwSpawnerConfig_TestEgg",
                  "EmptyItemId": "Test_Egg",
                  "AllowedRoles": {
                    "Mode": "Allowlist",
                    "Allowlist": []
                  }
                }
                """);
        AssetPatchDefinition patch = patch("""
                {
                  "Id": "SpawnerConfigPatch",
                  "Target": "Server/Tamework/Items/Spawners/TwSpawnerConfig_TestEgg.json",
                  "Operations": [
                    {
                      "Id": "allow-role",
                      "Op": "Add",
                      "Path": "/AllowedRoles/Allowlist/0",
                      "Value": "Mob_Test"
                    }
                  ]
                }
                """);

        AssetPatchEngine.PatchResult result = engine.apply(config, List.of(patch));

        assertEquals(
                "Mob_Test",
                result.patched().getAsJsonObject("AllowedRoles").getAsJsonArray("Allowlist").get(0).getAsString()
        );
        assertEquals(1, result.status().getApplied().size());
    }

    @Test
    void patchesNonJsonJsonLikeParticleSystemAssets() {
        JsonObject particleSystem = object("""
                {
                  "Emitters": [
                    {
                      "Id": "spark",
                      "Rate": 1
                    }
                  ]
                }
                """);
        AssetPatchDefinition patch = patch("""
                {
                  "Id": "ParticlePatch",
                  "Target": "Server/Particles/TameworkSpark.particlesystem",
                  "Operations": [
                    {
                      "Id": "rate",
                      "Op": "Replace",
                      "Path": "/Emitters/0/Rate",
                      "Value": 4
                    }
                  ]
                }
                """);

        AssetPatchEngine.PatchResult result = engine.apply(particleSystem, List.of(patch));

        assertEquals(4, result.patched().getAsJsonArray("Emitters").get(0).getAsJsonObject().get("Rate").getAsInt());
        assertEquals(1, result.status().getApplied().size());
    }

    @Test
    void requiredMissingAnchorFailsPatch() {
        JsonObject template = object("""
                {
                  "Instructions": []
                }
                """);
        AssetPatchDefinition patch = patch("""
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

        assertThrows(AssetPatchEngine.PatchFailureException.class, () -> engine.apply(template, List.of(patch)));
    }

    @Test
    void optionalMissingAnchorIsSkipped() {
        JsonObject template = object("""
                {
                  "Instructions": []
                }
                """);
        AssetPatchDefinition patch = patch("""
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

        AssetPatchEngine.PatchResult result = engine.apply(template, List.of(patch));

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
        AssetPatchDefinition patch = patch("""
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

        AssetPatchEngine.PatchResult result = engine.apply(template, List.of(patch));

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
        AssetPatchDefinition patch = patch("""
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

        AssetPatchEngine.PatchResult result = engine.apply(template, List.of(patch));

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
        assertEquals(
                "HasInteracted",
                result.patched().getAsJsonObject("InteractionInstruction").getAsJsonArray("Instructions")
                        .get(2).getAsJsonObject().getAsJsonObject("Sensor").get("Type").getAsString()
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

        AssetPatchDefinition patch = AssetPatchDefinition.parse(
                object(readResource("Server/Tamework/Patches/Examples/Tamework_Example_Patch.json")),
                "TestPack",
                "Server/Tamework/Patches/Examples/Tamework_Example_Patch.json"
        );
        AssetPatchEngine.PatchResult result = engine.apply(template, List.of(patch));
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
        assertEquals(
                "TwIntExamplePatch",
                actionByType(result.patched(), "TameworkInteractPrompt").get("ConfigId").getAsString()
        );
        assertEquals(
                "TwIntExamplePatch",
                actionByType(result.patched(), "TameworkInteract").get("ConfigId").getAsString()
        );
        assertStringArray(
                exportStates(instructionByComment(result.patched(), "Tamework patch: breed pair state")),
                "BreedPair",
                "Idle"
        );
        assertStringArray(
                exportStates(instructionByComment(result.patched(), "Tamework patch: sleep state")),
                "Defend",
                "Defend",
                "Idle",
                "Idle",
                "Idle"
        );
        for (JsonElement transition : result.patched().getAsJsonArray("StateTransitions")) {
            JsonObject transitionObject = transition.getAsJsonObject();
            assertTrue(transitionObject.has("States"), "StateTransition is missing States: " + transitionObject);
            assertTrue(transitionObject.has("Actions"), "StateTransition is missing Actions: " + transitionObject);
        }
        assertEquals(17, result.status().getApplied().size());
        assertEquals(0, result.status().getFailed().size());
    }

    @Test
    void patchExampleInteractionConfigUsesFollowStateForFollowingMode() throws Exception {
        JsonObject patchConfig = object(readResource("Server/Tamework/Interactions/TwIntExamplePatch.json"));
        JsonObject modeCycle = interactionByType(patchConfig, "ModeCycle");
        assertEquals("Follow", modeCycle.getAsJsonArray("Cycle").get(0).getAsJsonObject().get("State").getAsString());
        assertEquals("Following", modeCycle.getAsJsonArray("Cycle").get(0).getAsJsonObject().get("Message").getAsString());
        assertEquals("Idle", modeCycle.getAsJsonArray("Cycle").get(1).getAsJsonObject().get("State").getAsString());
        assertEquals("Wandering", modeCycle.getAsJsonArray("Cycle").get(1).getAsJsonObject().get("Message").getAsString());

        JsonObject sharedConfig = object(readResource("Server/Tamework/Interactions/TwIntExample.json"));
        for (JsonElement roleId : sharedConfig.getAsJsonArray("RoleIds")) {
            assertFalse("Mob_Tamework_Example_Patch".equals(roleId.getAsString()));
        }
    }

    private static AssetPatchDefinition patch(String json) {
        return AssetPatchDefinition.parse(object(json), "TestPack", "Server/Tamework/Patches/Test.json");
    }

    private void assertBucketPatch(String patchPath, String target, String emptyBucketItemId) throws Exception {
        JsonObject bucket = object("""
                {
                  "State": {
                    "Filled_Water": {
                      "Interactions": {
                        "Secondary": {
                          "Interactions": [
                            {
                              "Type": "PlaceFluid",
                              "RemoveItemInHand": false,
                              "FluidToPlace": "Water_Source"
                            }
                          ]
                        }
                      }
                    }
                  },
                  "TameworkCompatibilitySentinel": true
                }
                """);
        AssetPatchDefinition patch = AssetPatchDefinition.parse(
                object(readResource(patchPath)),
                "Alec's Tamework!",
                patchPath
        );

        AssetPatchEngine.PatchResult result = engine.apply(bucket, List.of(patch));
        JsonObject filledWater = result.patched()
                .getAsJsonObject("State")
                .getAsJsonObject("Filled_Water");
        JsonArray interactions = filledWater
                .getAsJsonObject("Interactions")
                .getAsJsonObject("Secondary")
                .getAsJsonArray("Interactions");
        JsonObject troughBranch = interactions.get(0).getAsJsonObject();
        JsonObject changeBlock = troughBranch.getAsJsonObject("Next");
        JsonObject consumeBucket = changeBlock.getAsJsonObject("Next");
        JsonObject fallback = troughBranch.getAsJsonObject("Failed");

        assertEquals(target, patch.getTarget());
        assertEquals(-100, patch.getPriority());
        assertTrue(result.patched().get("TameworkCompatibilitySentinel").getAsBoolean());
        assertEquals(1, interactions.size());
        assertEquals("BlockCondition", troughBranch.get("Type").getAsString());
        assertEquals(11, troughBranch.getAsJsonArray("Matchers").size());
        assertEquals("Tw_Feed_Trough", troughBranch.getAsJsonArray("Matchers")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("Block")
                .get("Id")
                .getAsString());
        assertEquals("ChangeBlock", changeBlock.get("Type").getAsString());
        assertEquals(
                "**Tw_Feed_Trough_State_Water_State_Full",
                changeBlock.getAsJsonObject("Changes").get("Tw_Feed_Trough").getAsString()
        );
        assertEquals("ModifyInventory", consumeBucket.get("Type").getAsString());
        assertEquals(emptyBucketItemId, consumeBucket.get("BrokenItem").getAsString());
        assertEquals("PlaceFluid", fallback.get("Type").getAsString());
        assertEquals("Water_Source", fallback.get("FluidToPlace").getAsString());
        assertEquals(
                emptyBucketItemId,
                fallback.getAsJsonObject("Next").get("BrokenItem").getAsString()
        );
        assertEquals(1, result.status().getApplied().size());
    }

    private static JsonObject instructionByComment(JsonObject root, String comment) {
        for (JsonElement instruction : root.getAsJsonArray("Instructions")) {
            JsonObject instructionObject = instruction.getAsJsonObject();
            if (instructionObject.has("$Comment") && comment.equals(instructionObject.get("$Comment").getAsString())) {
                return instructionObject;
            }
        }
        throw new AssertionError("Missing instruction comment " + comment);
    }

    private static JsonObject actionByType(JsonObject root, String type) {
        for (JsonElement instruction : root.getAsJsonObject("InteractionInstruction").getAsJsonArray("Instructions")) {
            JsonObject branch = instruction.getAsJsonObject();
            if (!branch.has("Actions")) {
                continue;
            }
            for (JsonElement action : branch.getAsJsonArray("Actions")) {
                JsonObject actionObject = action.getAsJsonObject();
                if (actionObject.has("Type") && type.equals(actionObject.get("Type").getAsString())) {
                    return actionObject;
                }
            }
        }
        throw new AssertionError("Missing interaction action type " + type);
    }

    private static JsonObject interactionByType(JsonObject config, String type) {
        for (JsonElement interaction : config.getAsJsonArray("Interactions")) {
            JsonObject interactionObject = interaction.getAsJsonObject();
            if (interactionObject.has("Type") && type.equals(interactionObject.get("Type").getAsString())) {
                return interactionObject;
            }
        }
        throw new AssertionError("Missing interaction type " + type);
    }

    private static JsonArray exportStates(JsonObject instruction) {
        JsonObject component = instruction.getAsJsonArray("Instructions").get(0).getAsJsonObject();
        return component.getAsJsonObject("Modify").getAsJsonArray("_ExportStates");
    }

    private static void assertStringArray(JsonArray actual, String... expected) {
        assertEquals(expected.length, actual.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual.get(index).getAsString());
        }
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String readResource(String path) throws Exception {
        ClassLoader loader = AssetPatchEngineTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            assertNotNull(stream, "Missing test resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
