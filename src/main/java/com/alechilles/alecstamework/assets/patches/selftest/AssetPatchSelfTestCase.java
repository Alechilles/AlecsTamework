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
                                    "Appearance": {
                                      "Value": "Tamework_Example"
                                    },
                                    "NameTranslationKey": {
                                      "Value": "server.npcRoles.TwPatchSelfTest_Template.name"
                                    },
                                    "MaxHealth": {
                                      "Value": 100
                                    },
                                    "MaxSpeed": {
                                      "Value": 4
                                    },
                                    "MaxRotationSpeed": {
                                      "Value": 360
                                    },
                                    "RunThreshold": {
                                      "Value": 0.5
                                    },
                                    "ClimbHeight": {
                                      "Value": 1
                                    },
                                    "WanderRadius": {
                                      "Value": 4
                                    },
                                    "PlayerDefaultAttitude": {
                                      "Value": "Neutral"
                                    },
                                    "AttitudeGroup": {
                                      "Value": "PreyBig"
                                    },
                                    "DropList": {
                                      "Value": "Empty"
                                    },
                                    "FlockArray": {
                                      "Value": [],
                                      "TypeHint": "String"
                                    },
                                    "BreathesInWater": {
                                      "Value": true
                                    },
                                    "PatchApplied": {
                                      "Value": false
                                    }
                                  },
                                  "IsMemory": false,
                                  "DropList": {
                                    "Compute": "DropList"
                                  },
                                  "FlockSpawnTypes": {
                                    "Compute": "FlockArray"
                                  },
                                  "FlockAllowedNPC": {
                                    "Compute": "FlockArray"
                                  },
                                  "FlockSpawnTypesRandom": false,
                                  "DisableDamageGroups": ["Self"],
                                  "Appearance": {
                                    "Compute": "Appearance"
                                  },
                                  "DefaultPlayerAttitude": {
                                    "Compute": "PlayerDefaultAttitude"
                                  },
                                  "DefaultNPCAttitude": "Ignore",
                                  "AttitudeGroup": {
                                    "Compute": "AttitudeGroup"
                                  },
                                  "ApplySeparation": true,
                                  "SeparationDistance": 1,
                                  "SeparationWeight": 1,
                                  "BreathesInWater": {
                                    "Compute": "BreathesInWater"
                                  },
                                  "KnockbackScale": 0.5,
                                  "MotionControllerList": [
                                    {
                                      "Type": "Walk",
                                      "MaxWalkSpeed": {
                                        "Compute": "MaxSpeed"
                                      },
                                      "Gravity": 15,
                                      "MaxFallSpeed": 15,
                                      "JumpHeight": 0.1,
                                      "MaxRotationSpeed": {
                                        "Compute": "MaxRotationSpeed"
                                      },
                                      "Acceleration": 100,
                                      "RunThreshold": {
                                        "Compute": "RunThreshold"
                                      },
                                      "DescentAnimationType": "Fall",
                                      "DescentSteepness": 2.5,
                                      "DescentBlending": 1,
                                      "JumpDescentSteepness": 8,
                                      "MaxClimbHeight": {
                                        "Compute": "ClimbHeight"
                                      }
                                    }
                                  ],
                                  "StateTransitions": [
                                    {
                                      "States": [
                                        {
                                          "To": ["Idle"],
                                          "From": []
                                        }
                                      ],
                                      "Actions": [
                                        {
                                          "Type": "ReleaseTarget"
                                        },
                                        {
                                          "Type": "ResetInstructions"
                                        },
                                        {
                                          "Type": "PlayAnimation",
                                          "Slot": "Status"
                                        }
                                      ]
                                    }
                                  ],
                                  "MaxHealth": {
                                    "Compute": "MaxHealth"
                                  },
                                  "Instructions": [
                                    {
                                      "$Comment": "Patch self-test anchor",
                                      "Sensor": {
                                        "Type": "State",
                                        "State": "Idle"
                                      },
                                      "Instructions": [
                                        {
                                          "BodyMotion": {
                                            "Type": "WanderInCircle",
                                            "Radius": {
                                              "Compute": "WanderRadius"
                                            },
                                            "MaxHeadingChange": 90,
                                            "RelativeSpeed": 0.4,
                                            "RelaxedMoveConstraints": true
                                          }
                                        }
                                      ]
                                    }
                                  ],
                                  "InteractionInstruction": {
                                    "Instructions": [
                                      {
                                        "Sensor": {
                                          "Type": "Any"
                                        },
                                        "Actions": [
                                          {
                                            "Type": "SetInteractable",
                                            "Interactable": false
                                          }
                                        ]
                                      }
                                    ]
                                  },
                                  "NameTranslationKey": {
                                    "Compute": "NameTranslationKey"
                                  }
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
                                  "TranslationProperties": {
                                    "Name": "server.items.TwPatchSelfTest_CommandItem.name",
                                    "Description": "server.items.TwPatchSelfTest_CommandItem.description"
                                  },
                                  "Quality": "Tool",
                                  "PlayerAnimationsId": "Item",
                                  "Interactions": {
                                    "Primary": {
                                      "Interactions": []
                                    }
                                  },
                                  "Model": "Items/Consumables/Recipes/Recipe.blockymodel",
                                  "Texture": "Items/Consumables/Recipes/Recipe_Texture.png",
                                  "Icon": "Icons/ItemsGenerated/Recipe_Page.png",
                                  "IconProperties": {
                                    "Scale": 0.76,
                                    "Translation": [
                                      -1,
                                      5
                                    ],
                                    "Rotation": [
                                      135,
                                      135,
                                      0
                                    ]
                                  },
                                  "MaxStack": 1
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
                                      "Path": "/Interactions/Primary/Interactions",
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
                        AssetPatchReloadMode.RESTART_REQUIRED,
                        ReloadRequirement.HOT_RELOAD_OR_RESTART_REQUIRED,
                        List.of(
                                checkString("/Interactions/Primary/Interactions/0/Type", "TameworkCommand"),
                                checkString("/Interactions/Primary/Interactions/0/ConfigId", "TwCommandItem_PatchSelfTest")
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
                        ReloadRequirement.HOT_RELOAD_OR_RESTART_REQUIRED,
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
                                  "Spawners": [
                                    {
                                      "SpawnerId": "Explosion_Small_Smoke_Main",
                                      "PositionOffset": {
                                        "Y": 0.5
                                      },
                                      "StartDelay": 0.1
                                    }
                                  ],
                                  "BoundingRadius": 100.0,
                                  "CullDistance": 100.0,
                                  "IsImportant": false
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
                                      "Path": "/Spawners/0/StartDelay",
                                      "Value": 0.25
                                    }
                                  ]
                                }
                                """,
                        AssetPatchReloadMode.RESTART_REQUIRED,
                        ReloadRequirement.HOT_RELOAD_OR_RESTART_REQUIRED,
                        List.of(check("/Spawners/0/StartDelay", "0.25"))
                ),
                new AssetPatchSelfTestCase(
                        "common-asset",
                        "Common/Tamework/SelfTest/TwPatchSelfTest_Common.json",
                        """
                                {
                                  "PatchApplied": false
                                }
                                """,
                        PATCH_ROOT + "90_Common.json",
                        """
                                {
                                  "Id": "TwPatchSelfTest_Common",
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
                        ReloadRequirement.HOT_RELOAD_OR_RESTART_REQUIRED,
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
