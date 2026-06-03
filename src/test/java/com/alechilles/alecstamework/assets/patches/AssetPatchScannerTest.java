package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.hypixel.hytale.assetstore.AssetPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AssetPatchScannerTest {

    @TempDir
    private Path tempDir;

    @Test
    void scansPatchDefinitionsFromRegisteredPackRoots() throws Exception {
        Path packRoot = tempDir.resolve("patch-pack");
        Path patchDir = packRoot.resolve(AssetPatchScanner.PATCH_DIRECTORY).resolve("AnimalHusbandry/Livestock");
        Files.createDirectories(patchDir);
        Files.writeString(
                patchDir.resolve("Livestock.json"),
                """
                        {
                          "Id": "LivestockPatch",
                          "Target": "Server/NPC/Roles/_Core/Templates/AH_Template_Livestock.json",
                          "Operations": [
                            {
                              "Id": "flag",
                              "Op": "Add",
                              "Path": "/Patched",
                              "Value": true
                            }
                          ]
                        }
                        """,
                StandardCharsets.UTF_8
        );

        AssetPatchStatus status = new AssetPatchStatus();
        List<AssetPatchDefinition> definitions = new AssetPatchScanner(null)
                .scan(List.of(pack("ModPack", packRoot)), "GeneratedPack", status);

        assertEquals(1, definitions.size());
        assertEquals("LivestockPatch", definitions.getFirst().getId());
        assertEquals("Server/NPC/Roles/_Core/Templates/AH_Template_Livestock.json", definitions.getFirst().getTarget());
        assertEquals(0, status.getFailed().size());
    }

    @Test
    void expandsPatchDefinitionsForMultipleTargets() throws Exception {
        Path packRoot = tempDir.resolve("multi-target-pack");
        Path patchDir = packRoot.resolve(AssetPatchScanner.PATCH_DIRECTORY).resolve("Shared");
        Files.createDirectories(patchDir);
        Files.writeString(
                patchDir.resolve("SharedPatch.json"),
                """
                        {
                          "Id": "SharedPatch",
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
                        """,
                StandardCharsets.UTF_8
        );

        AssetPatchStatus status = new AssetPatchStatus();
        List<AssetPatchDefinition> definitions = new AssetPatchScanner(null)
                .scan(List.of(pack("ModPack", packRoot)), "GeneratedPack", status);

        assertEquals(2, definitions.size());
        assertEquals("Server/NPC/Roles/_Core/Templates/Cow.json", definitions.get(0).getTarget());
        assertEquals("Server/NPC/Roles/_Core/Templates/Sheep.json", definitions.get(1).getTarget());
        assertEquals("SharedPatch", definitions.get(0).getId());
        assertEquals("SharedPatch", definitions.get(1).getId());
        assertEquals(0, status.getFailed().size());
    }

    @Test
    void skipsPatchWhenConditionIsNotMet() throws Exception {
        Path packRoot = tempDir.resolve("conditional-pack");
        Path patchDir = packRoot.resolve(AssetPatchScanner.PATCH_DIRECTORY).resolve("Shared");
        Files.createDirectories(patchDir);
        Files.writeString(
                patchDir.resolve("AnimalHusbandryPatch.json"),
                """
                        {
                          "Id": "AnimalHusbandryPatch",
                          "Target": "Server/NPC/Roles/_Core/Templates/Cow.json",
                          "When": {
                            "ModInstalled": "alec:animal_husbandry"
                          },
                          "Operations": [
                            {
                              "Id": "flag",
                              "Op": "Add",
                              "Path": "/Patched",
                              "Value": true
                            }
                          ]
                        }
                        """,
                StandardCharsets.UTF_8
        );

        AssetPatchStatus status = new AssetPatchStatus();
        List<AssetPatchDefinition> definitions = new AssetPatchScanner(null)
                .scan(List.of(pack("ModPack", packRoot)), "GeneratedPack", status);

        assertEquals(0, definitions.size());
        assertEquals(1, status.getSkipped().size());
        assertTrue(status.getSkipped().getFirst().contains("condition not met"));
        assertEquals(0, status.getFailed().size());
    }

    @Test
    void addsPatchWhenConditionIsMet() throws Exception {
        Path packRoot = tempDir.resolve("conditional-pack");
        Path dependencyRoot = tempDir.resolve("animal-husbandry-pack");
        Path patchDir = packRoot.resolve(AssetPatchScanner.PATCH_DIRECTORY).resolve("Shared");
        Files.createDirectories(patchDir);
        Files.createDirectories(dependencyRoot);
        Files.writeString(
                patchDir.resolve("AnimalHusbandryPatch.json"),
                """
                        {
                          "Id": "AnimalHusbandryPatch",
                          "Target": "Server/NPC/Roles/_Core/Templates/Cow.json",
                          "When": {
                            "ModInstalled": "alec:animal_husbandry"
                          },
                          "Operations": [
                            {
                              "Id": "flag",
                              "Op": "Add",
                              "Path": "/Patched",
                              "Value": true
                            }
                          ]
                        }
                        """,
                StandardCharsets.UTF_8
        );

        AssetPatchStatus status = new AssetPatchStatus();
        List<AssetPatchDefinition> definitions = new AssetPatchScanner(null)
                .scan(
                        List.of(pack("ModPack", packRoot), pack("alec:animal_husbandry", dependencyRoot)),
                        "GeneratedPack",
                        status
                );

        assertEquals(1, definitions.size());
        assertEquals("AnimalHusbandryPatch", definitions.getFirst().getId());
        assertEquals(0, status.getSkipped().size());
        assertEquals(0, status.getFailed().size());
    }

    @Test
    void targetResolverUsesLastRegisteredPackAsWinningSource() throws Exception {
        Path baseRoot = tempDir.resolve("base-pack");
        Path overrideRoot = tempDir.resolve("override-pack");
        String target = "Server/NPC/Roles/_Core/Templates/Test.json";
        writeTemplate(baseRoot, target, "Base");
        writeTemplate(overrideRoot, target, "Override");

        AssetPatchTargetResolver resolver = new AssetPatchTargetResolver();
        AssetPatchTargetResolver.TargetSource source = resolver.resolve(
                List.of(pack("Base", baseRoot), pack("Override", overrideRoot)),
                "GeneratedPack",
                target
        );

        assertNotNull(source);
        assertEquals("Override", source.packId());
        assertEquals("Override", resolver.readAsset(source).get("Name").getAsString());
    }

    private static void writeTemplate(Path root, String target, String name) throws Exception {
        Path output = root.resolve(target);
        Files.createDirectories(output.getParent());
        Files.writeString(output, "{ \"Name\": \"" + name + "\" }", StandardCharsets.UTF_8);
    }

    private static AssetPack pack(String name, Path root) {
        return new AssetPack(root, name, root, FileSystems.getDefault(), false, null, AssetPack.PackSource.RUNTIME);
    }
}
