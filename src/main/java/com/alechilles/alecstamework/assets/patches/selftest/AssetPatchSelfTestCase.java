package com.alechilles.alecstamework.assets.patches.selftest;

import java.util.List;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.assets.patches.AssetPatchReloadMode;
import com.alechilles.alecstamework.assets.patches.AssetPatchTargetClassifier;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * Describes one isolated asset patch self-test target and the generated JSON checks expected after reload.
 */
public record AssetPatchSelfTestCase(@Nonnull String id,
                                     @Nonnull String sourcePath,
                                     @Nonnull String sourceJsonTemplate,
                                     @Nonnull String patchPath,
                                     @Nonnull String patchJsonTemplate,
                                     @Nonnull AssetPatchReloadMode expectedReloadMode,
                                     @Nonnull ReloadRequirement reloadRequirement,
                                     @Nonnull List<GeneratedCheck> generatedChecks) {
    public static final String RUN_ID_TOKEN = "__RUN_ID__";
    private static final String PATCH_ROOT = "Server/Tamework/Patches/SelfTest/";

    public AssetPatchSelfTestCase {
        sourcePath = normalizeAssetPath(sourcePath);
        patchPath = normalizeAssetPath(patchPath);
        generatedChecks = List.copyOf(generatedChecks);
        if (sourcePath.startsWith("Server/Tamework/Patches/")) {
            throw new IllegalArgumentException("Self-test source path must not be a patch path: " + sourcePath);
        }
        if (!patchPath.startsWith(PATCH_ROOT)) {
            throw new IllegalArgumentException("Self-test patch path must live under " + PATCH_ROOT + ": " + patchPath);
        }
        AssetPatchReloadMode classifiedMode = AssetPatchTargetClassifier.classify(sourcePath).reloadMode();
        if (classifiedMode != expectedReloadMode) {
            throw new IllegalArgumentException(
                    "Expected reload mode " + expectedReloadMode + " but target classifies as " + classifiedMode
            );
        }
    }

    @Nonnull
    public String sourceJson(@Nonnull String runId) {
        return replaceRunId(sourceJsonTemplate, runId);
    }

    @Nonnull
    public String patchJson(@Nonnull String runId) {
        return replaceRunId(patchJsonTemplate, runId);
    }

    @Nonnull
    private static String replaceRunId(@Nonnull String template, @Nonnull String runId) {
        return template.replace(RUN_ID_TOKEN, runId);
    }

    public boolean expectsHotReload() {
        return reloadRequirement == ReloadRequirement.REQUIRED_HOT_RELOAD;
    }

    public boolean acceptsRestartRequired() {
        return reloadRequirement == ReloadRequirement.RESTART_REQUIRED_EXPECTED
                || reloadRequirement == ReloadRequirement.HOT_RELOAD_OR_RESTART_REQUIRED;
    }

    public enum ReloadRequirement {
        REQUIRED_HOT_RELOAD,
        HOT_RELOAD_OR_RESTART_REQUIRED,
        RESTART_REQUIRED_EXPECTED
    }

    public record GeneratedCheck(@Nonnull String path, @Nonnull JsonElement expectedValue) {
    }

    @Nonnull
    static List<AssetPatchSelfTestCase> defaultCases() {
        return List.of(
                new AssetPatchSelfTestCase(
                        "npc-template",
                        "Server/NPC/Roles/_Core/Templates/TwPatchSelfTest_Template.json",
                        """
                                {
                                  "Type": "Abstract",
                                  "StartState": "Idle",
                                  "Parameters": {
                                    "PatchApplied": {
                                      "Value": false
                                    }
                                  },
                                  "Instructions": [
                                    {
                                      "$Comment": "Patch self-test anchor"
                                    }
                                  ]
                                }
                                """,
                        PATCH_ROOT + "10_Template.json",
                        """
                                {
                                  "Id": "TwPatchSelfTest_Template",
                                  "Target": "Server/NPC/Roles/_Core/Templates/TwPatchSelfTest_Template.json",
                                  "Operations": [
                                    {
                                      "Id": "set-marker",
                                      "Op": "Replace",
                                      "Path": "/Parameters/PatchApplied/Value",
                                      "Value": true
                                    },
                                    {
                                      "Id": "add-run-id",
                                      "Op": "Add",
                                      "Path": "/Parameters/PatchRunId",
                                      "Value": {
                                        "Value": "__RUN_ID__"
                                      }
                                    }
                                  ]
                                }
                                """,
                        AssetPatchReloadMode.NPC_BUILDERS,
                        ReloadRequirement.REQUIRED_HOT_RELOAD,
                        List.of(
                                check("/Parameters/PatchApplied/Value", "true"),
                                checkString("/Parameters/PatchRunId/Value", RUN_ID_TOKEN)
                        )
                ),
                new AssetPatchSelfTestCase(
                        "item-action",
                        "Server/Item/Items/Tamework/SelfTest/TwPatchSelfTest_CommandItem.json",
                        """
                                {
                                  "Id": "TwPatchSelfTest_CommandItem",
                                  "DisplayName": "Patch Self-Test Command Item",
                                  "RootItemInteraction": {
                                    "Actions": [
                                      {
                                        "Type": "Inspect"
                                      }
                                    ]
                                  }
                                }
                                """,
                        PATCH_ROOT + "20_Item.json",
                        """
                                {
                                  "Id": "TwPatchSelfTest_CommandItemAction",
                                  "Target": "Server/Item/Items/Tamework/SelfTest/TwPatchSelfTest_CommandItem.json",
                                  "Operations": [
                                    {
                                      "Id": "add-command-action",
                                      "Op": "Insert",
                                      "Path": "/RootItemInteraction/Actions",
                                      "Position": "End",
                                      "Existing": {
                                        "Type": "TameworkCommand"
                                      },
                                      "Value": {
                                        "Type": "TameworkCommand",
                                        "ConfigId": "TwCommandItem_PatchSelfTest"
                                      }
                                    }
                                  ]
                                }
                                """,
                        AssetPatchReloadMode.HYTALE_ASSET_STORE,
                        ReloadRequirement.HOT_RELOAD_OR_RESTART_REQUIRED,
                        List.of(
                                checkString("/RootItemInteraction/Actions/1/Type", "TameworkCommand"),
                                checkString("/RootItemInteraction/Actions/1/ConfigId", "TwCommandItem_PatchSelfTest")
                        )
                ),
                new AssetPatchSelfTestCase(
                        "tamework-config",
                        "Server/Tamework/Items/Commands/TwCommandItem_PatchSelfTest.json",
                        """
                                {
                                  "Id": "TwCommandItem_PatchSelfTest",
                                  "Enabled": true,
                                  "ItemIds": [
                                    "TwPatchSelfTest_CommandItem"
                                  ],
                                  "CommandList": []
                                }
                                """,
                        PATCH_ROOT + "30_CommandConfig.json",
                        """
                                {
                                  "Id": "TwPatchSelfTest_CommandConfig",
                                  "Target": "Server/Tamework/Items/Commands/TwCommandItem_PatchSelfTest.json",
                                  "Operations": [
                                    {
                                      "Id": "add-selftest-command",
                                      "Op": "Add",
                                      "Path": "/CommandList/0",
                                      "Value": {
                                        "Id": "SelfTest",
                                        "DisplayName": "Self Test",
                                        "Steps": []
                                      }
                                    }
                                  ]
                                }
                                """,
                        AssetPatchReloadMode.TAMEWORK_CONFIG,
                        ReloadRequirement.REQUIRED_HOT_RELOAD,
                        List.of(
                                checkString("/CommandList/0/Id", "SelfTest"),
                                checkString("/CommandList/0/DisplayName", "Self Test")
                        )
                ),
                new AssetPatchSelfTestCase(
                        "particle-system",
                        "Server/Particles/Tamework/TwPatchSelfTest.particlesystem",
                        """
                                {
                                  "Emitters": [
                                    {
                                      "Id": "spark",
                                      "Rate": 1
                                    }
                                  ]
                                }
                                """,
                        PATCH_ROOT + "40_Particle.json",
                        """
                                {
                                  "Id": "TwPatchSelfTest_Particle",
                                  "Target": "Server/Particles/Tamework/TwPatchSelfTest.particlesystem",
                                  "Operations": [
                                    {
                                      "Id": "increase-rate",
                                      "Op": "Replace",
                                      "Path": "/Emitters/0/Rate",
                                      "Value": 4
                                    }
                                  ]
                                }
                                """,
                        AssetPatchReloadMode.HYTALE_ASSET_STORE,
                        ReloadRequirement.HOT_RELOAD_OR_RESTART_REQUIRED,
                        List.of(check("/Emitters/0/Rate", "4"))
                ),
                new AssetPatchSelfTestCase(
                        "common-restart-required",
                        "Common/Tamework/SelfTest/TwPatchSelfTest_Common.json",
                        """
                                {
                                  "PatchApplied": false
                                }
                                """,
                        PATCH_ROOT + "90_CommonRestartRequired.json",
                        """
                                {
                                  "Id": "TwPatchSelfTest_CommonRestartRequired",
                                  "Target": "Common/Tamework/SelfTest/TwPatchSelfTest_Common.json",
                                  "Operations": [
                                    {
                                      "Id": "set-marker",
                                      "Op": "Replace",
                                      "Path": "/PatchApplied",
                                      "Value": true
                                    }
                                  ]
                                }
                                """,
                        AssetPatchReloadMode.RESTART_REQUIRED,
                        ReloadRequirement.RESTART_REQUIRED_EXPECTED,
                        List.of(check("/PatchApplied", "true"))
                )
        );
    }

    @Nonnull
    private static GeneratedCheck check(@Nonnull String path, @Nonnull String expectedJson) {
        return new GeneratedCheck(path, com.google.gson.JsonParser.parseString(expectedJson));
    }

    @Nonnull
    private static GeneratedCheck checkString(@Nonnull String path, @Nonnull String expectedValue) {
        return new GeneratedCheck(path, new JsonPrimitive(expectedValue));
    }

    @Nonnull
    static String normalizeAssetPath(@Nonnull String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
