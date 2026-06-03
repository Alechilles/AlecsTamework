package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AssetPatchConditionTest {
    @TempDir
    private Path tempDir;

    @Test
    void modInstalledMatchesRegisteredPack() {
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "ModInstalled": "alec:animal_husbandry"
                }
                """));
        AssetPatchConditionContext context = new AssetPatchConditionContext(
                "generated:patches",
                List.of("alec:animal_husbandry", "other:mod", "generated:patches")
        );

        assertTrue(condition.matches(context));
        assertEquals("ModInstalled alec:animal_husbandry", condition.describe());
    }

    @Test
    void modInstalledTrimsConfiguredPackId() {
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "ModInstalled": " alec:animal_husbandry "
                }
                """));
        AssetPatchConditionContext context = new AssetPatchConditionContext(
                "generated:patches",
                List.of("alec:animal_husbandry")
        );

        assertTrue(condition.matches(context));
        assertEquals("ModInstalled alec:animal_husbandry", condition.describe());
    }

    @Test
    void modInstalledDoesNotMatchMissingPack() {
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "ModInstalled": "alec:animal_husbandry"
                }
                """));
        AssetPatchConditionContext context = new AssetPatchConditionContext(
                "generated:patches",
                List.of("other:mod", "generated:patches")
        );

        assertFalse(condition.matches(context));
    }

    @Test
    void assetExistsAndAssetMissingReadRegisteredPackRoots() throws Exception {
        String target = "Server/NPC/Roles/Test.json";
        Path packRoot = tempDir.resolve("content-pack");
        writeJson(packRoot, target, "{ \"Name\": \"Test\" }");
        AssetPatchCondition exists = AssetPatchCondition.parse(object("""
                {
                  "AssetExists": "Server/NPC/Roles/Test.json"
                }
                """));
        AssetPatchCondition missing = AssetPatchCondition.parse(object("""
                {
                  "AssetMissing": "Server/NPC/Roles/Missing.json"
                }
                """));
        AssetPatchConditionContext context = context(List.of(
                AssetPatchConditionContext.packInfo("content:pack", packRoot)
        ), null);

        assertTrue(exists.matches(context));
        assertTrue(missing.matches(context));
    }

    @Test
    void targetExistsUsesExpandedPatchTarget() throws Exception {
        String target = "Server/NPC/Roles/Existing.json";
        Path packRoot = tempDir.resolve("content-pack");
        writeJson(packRoot, target, "{ \"Name\": \"Existing\" }");
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "TargetExists": true
                }
                """));
        AssetPatchConditionContext context = context(List.of(
                AssetPatchConditionContext.packInfo("content:pack", packRoot)
        ), null);

        assertTrue(condition.matches(context, target));
        assertFalse(condition.matches(context, "Server/NPC/Roles/Missing.json"));
    }

    @Test
    void modVersionComparesPackManifestVersion() throws Exception {
        Path packRoot = tempDir.resolve("versioned-pack");
        Files.createDirectories(packRoot);
        Files.writeString(
                packRoot.resolve("manifest.json"),
                "{ \"Version\": \"2.12.3\" }",
                StandardCharsets.UTF_8
        );
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "ModVersion": {
                    "Mod": "alec:animal_husbandry",
                    "AtLeast": "2.12.0",
                    "Below": "3.0.0"
                  }
                }
                """));
        AssetPatchConditionContext context = context(List.of(
                AssetPatchConditionContext.packInfo("alec:animal_husbandry", packRoot)
        ), null);

        assertTrue(condition.matches(context));
    }

    @Test
    void serverVersionUsesConfiguredRuntimeVersion() {
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "GameVersion": {
                    "AtLeast": "0.5.0",
                    "Below": "0.6.0"
                  }
                }
                """));
        AssetPatchConditionContext context = context(List.of(), "0.5.3");

        assertTrue(condition.matches(context));
    }

    @Test
    void jsonPathConditionsReadWinningAssetSource() throws Exception {
        String target = "Server/NPC/Roles/Test.json";
        Path baseRoot = tempDir.resolve("base-pack");
        Path overrideRoot = tempDir.resolve("override-pack");
        writeJson(baseRoot, target, "{ \"Name\": \"Base\", \"Components\": {} }");
        writeJson(overrideRoot, target, """
                {
                  "Name": "Override",
                  "Components": {
                    "TameworkOwner": {
                      "Enabled": true
                    }
                  }
                }
                """);
        AssetPatchCondition exists = AssetPatchCondition.parse(object("""
                {
                  "JsonPathExists": {
                    "Asset": "$Target",
                    "Path": "/Components/TameworkOwner"
                  }
                }
                """));
        AssetPatchCondition equals = AssetPatchCondition.parse(object("""
                {
                  "JsonPathEquals": {
                    "Asset": "$Target",
                    "Path": "/Name",
                    "Value": "Override"
                  }
                }
                """));
        AssetPatchConditionContext context = context(List.of(
                AssetPatchConditionContext.packInfo("base:pack", baseRoot),
                AssetPatchConditionContext.packInfo("override:pack", overrideRoot)
        ), null);

        assertTrue(exists.matches(context, target));
        assertTrue(equals.matches(context, target));
    }

    @Test
    void tameworkSettingMatchesResolvedSettings() {
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "TameworkSetting": {
                    "Path": "traits.enabled",
                    "Equals": true
                  }
                }
                """));
        AssetPatchConditionContext context = context(List.of(), null);

        assertTrue(condition.matches(context));
    }

    @Test
    void allRequiresEveryChild() {
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "All": [
                    { "ModInstalled": "alec:animal_husbandry" },
                    { "Not": { "ModInstalled": "conflicting:mod" } }
                  ]
                }
                """));
        AssetPatchConditionContext context = new AssetPatchConditionContext(
                "generated:patches",
                List.of("alec:animal_husbandry")
        );

        assertTrue(condition.matches(context));
    }

    @Test
    void anyRequiresOneChild() {
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "Any": [
                    { "ModInstalled": "missing:mod" },
                    { "ModInstalled": "alec:animal_husbandry" }
                  ]
                }
                """));
        AssetPatchConditionContext context = new AssetPatchConditionContext(
                "generated:patches",
                List.of("alec:animal_husbandry")
        );

        assertTrue(condition.matches(context));
    }

    @Test
    void notNegatesChild() {
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "Not": { "ModInstalled": "conflicting:mod" }
                }
                """));
        AssetPatchConditionContext context = new AssetPatchConditionContext(
                "generated:patches",
                List.of("alec:animal_husbandry")
        );

        assertTrue(condition.matches(context));
    }

    @Test
    void ignoresCommentFieldsInsideConditions() {
        AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                {
                  "$Comment": "Only patch when AH is available.",
                  "All": [
                    {
                      "$Comment": "Dependency gate.",
                      "ModInstalled": "alec:animal_husbandry"
                    },
                    {
                      "$Comment": "Avoid conflicting pack.",
                      "Not": {
                        "$Comment": "Negated dependency gate.",
                        "ModInstalled": "conflicting:mod"
                      }
                    }
                  ]
                }
                """));
        AssetPatchConditionContext context = new AssetPatchConditionContext(
                "generated:patches",
                List.of("alec:animal_husbandry")
        );

        assertTrue(condition.matches(context));
    }

    @Test
    void conditionObjectMustUseOneRecognizedKey() {
        assertThrows(IllegalArgumentException.class, () -> AssetPatchCondition.parse(object("""
                {
                  "ModInstalled": "alec:animal_husbandry",
                  "Any": [
                    { "ModInstalled": "other:mod" }
                  ]
                }
                """)));
    }

    @Test
    void allAndAnyMustNotBeEmpty() {
        assertThrows(IllegalArgumentException.class, () -> AssetPatchCondition.parse(object("""
                {
                  "All": []
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> AssetPatchCondition.parse(object("""
                {
                  "Any": []
                }
                """)));
    }

    private AssetPatchConditionContext context(List<AssetPatchConditionContext.PackInfo> packs, String serverVersion) {
        return new AssetPatchConditionContext(
                "generated:patches",
                packs,
                TameworkSettingsStore.defaultGlobalSettings(),
                serverVersion
        );
    }

    private static void writeJson(Path root, String target, String json) throws Exception {
        Path output = root.resolve(target);
        Files.createDirectories(output.getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
