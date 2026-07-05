package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.alechilles.alecstamework.settings.ResolvedTameworkSettings;
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
    void runtimeServerVersionDoesNotFallbackToPluginManifestRange() {
        String originalServerVersion = System.getProperty("hytale.server.version");
        String originalGameVersion = System.getProperty("hytale.game.version");
        try {
            System.clearProperty("hytale.server.version");
            System.clearProperty("hytale.game.version");

            assertNull(AssetPatchConditionContext.runtimeServerVersion());
        } finally {
            restoreProperty("hytale.server.version", originalServerVersion);
            restoreProperty("hytale.game.version", originalGameVersion);
        }
    }

    @Test
    void versionMatchersRejectWildcards() {
        assertThrows(IllegalArgumentException.class, () -> AssetPatchCondition.parse(object("""
                {
                  "GameVersion": {
                    "AtLeast": "0.5.x"
                  }
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> AssetPatchCondition.parse(object("""
                {
                  "GameVersion": {
                    "AtLeast": "latest"
                  }
                }
                """)));
    }

    @Test
    void assetConditionsDoNotReadOutsidePackRoot() throws Exception {
        Path packRoot = tempDir.resolve("content-pack");
        Files.createDirectories(packRoot);
        Files.writeString(tempDir.resolve("manifest.json"), "{ \"Version\": \"outside\" }", StandardCharsets.UTF_8);
        AssetPatchCondition assetExists = AssetPatchCondition.parse(object("""
                {
                  "AssetExists": "../manifest.json"
                }
                """));
        AssetPatchCondition jsonPathExists = AssetPatchCondition.parse(object("""
                {
                  "JsonPathExists": {
                    "Asset": "../manifest.json",
                    "Path": "/Version"
                  }
                }
                """));
        AssetPatchConditionContext context = context(List.of(
                AssetPatchConditionContext.packInfo("content:pack", packRoot)
        ), null);

        assertFalse(assetExists.matches(context));
        assertFalse(jsonPathExists.matches(context));
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
    void tameworkSettingSupportsAccessorAndPersistedAliases() {
        ResolvedTameworkSettings settings = TameworkSettingsStore.defaultGlobalSettings();
        AssetPatchConditionContext context = new AssetPatchConditionContext(
                "generated:patches",
                List.of(),
                settings,
                null
        );

        for (SettingCase settingCase : settingCases(settings)) {
            AssetPatchCondition condition = AssetPatchCondition.parse(object("""
                    {
                      "TameworkSetting": {
                        "Path": "%s",
                        "Equals": %s
                      }
                    }
                    """.formatted(settingCase.path(), settingCase.valueJson())));

            assertTrue(condition.matches(context), settingCase.path());
        }
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

    private static List<SettingCase> settingCases(ResolvedTameworkSettings settings) {
        return List.of(
                setting("populationLimitPerPlayerOwnedTotal", settings.populationLimitPerPlayerOwnedTotal()),
                setting("population.limitPerPlayerOwnedTotal", settings.populationLimitPerPlayerOwnedTotal()),
                setting("populationPerPlayerLimitScope", settings.populationPerPlayerLimitScope()),
                setting("population.perPlayerLimitScope", settings.populationPerPlayerLimitScope()),
                setting("simpleClaimsEnabled", settings.simpleClaimsEnabled()),
                setting("simpleClaims.simpleClaimsEnabled", settings.simpleClaimsEnabled()),
                setting("simpleClaims.enabled", settings.simpleClaimsEnabled()),
                setting("simpleClaimsLimitPerClaimChunk", settings.simpleClaimsLimitPerClaimChunk()),
                setting("simpleClaims.limitPerClaimChunk", settings.simpleClaimsLimitPerClaimChunk()),
                setting("simpleClaimsLimitPerClaimTotal", settings.simpleClaimsLimitPerClaimTotal()),
                setting("simpleClaims.limitPerClaimTotal", settings.simpleClaimsLimitPerClaimTotal()),
                setting("simpleClaimsBreedingRequiresClaim", settings.simpleClaimsBreedingRequiresClaim()),
                setting("simpleClaims.breedingRequiresClaim", settings.simpleClaimsBreedingRequiresClaim()),
                setting("simpleClaimsProtectTamedFromNonMembers", settings.simpleClaimsProtectTamedFromNonMembers()),
                setting("simpleClaims.protectTamedFromNonMembers", settings.simpleClaimsProtectTamedFromNonMembers()),
                setting("blockOwnerDamage", settings.blockOwnerDamage()),
                setting("ownership.damageProtection.blockOwnerDamage", settings.blockOwnerDamage()),
                setting("blockAllPlayerDamageIfOwned", settings.blockAllPlayerDamageIfOwned()),
                setting("ownership.damageProtection.blockAllPlayerDamageIfOwned", settings.blockAllPlayerDamageIfOwned()),
                setting("invulnerableIfOwned", settings.invulnerableIfOwned()),
                setting("ownership.damageProtection.invulnerableIfOwned", settings.invulnerableIfOwned()),
                setting("captureClearsOwner", settings.captureClearsOwner()),
                setting("ownership.capture.captureClearsOwner", settings.captureClearsOwner()),
                setting("spawner.captureClearsOwner", settings.captureClearsOwner()),
                setting("spawnSetsOwner", settings.spawnSetsOwner()),
                setting("ownership.capture.spawnSetsOwner", settings.spawnSetsOwner()),
                setting("spawner.spawnSetsOwner", settings.spawnSetsOwner()),
                setting("captureRequiresOwner", settings.captureRequiresOwner()),
                setting("ownership.capture.captureRequiresOwner", settings.captureRequiresOwner()),
                setting("spawnRequiresOwner", settings.spawnRequiresOwner()),
                setting("ownership.capture.spawnRequiresOwner", settings.spawnRequiresOwner()),
                setting("interactionRequiresOwner", settings.interactionRequiresOwner()),
                setting("ownership.interactionRequiresOwner", settings.interactionRequiresOwner()),
                setting("linkingRequiresOwner", settings.linkingRequiresOwner()),
                setting("ownership.linkingRequiresOwner", settings.linkingRequiresOwner()),
                setting("needsEnabled", settings.needsEnabled()),
                setting("needs.enabled", settings.needsEnabled()),
                setting("needsResourceMode", settings.needsResourceMode()),
                setting("needs.resourceMode", settings.needsResourceMode()),
                setting("needsTickPolicyMode", settings.needsTickPolicyMode()),
                setting("needs.tickPolicy.mode", settings.needsTickPolicyMode()),
                setting("needs.tickPolicyMode", settings.needsTickPolicyMode()),
                setting("needsOwnerOfflineGraceHours", settings.needsOwnerOfflineGraceHours()),
                setting("needs.tickPolicy.ownerOfflineGraceHours", settings.needsOwnerOfflineGraceHours()),
                setting("needs.ownerOfflineGraceHours", settings.needsOwnerOfflineGraceHours()),
                setting("needsOwnerOfflineDecayMultiplier", settings.needsOwnerOfflineDecayMultiplier()),
                setting("needs.tickPolicy.ownerOfflineDecayMultiplier", settings.needsOwnerOfflineDecayMultiplier()),
                setting("needs.ownerOfflineDecayMultiplier", settings.needsOwnerOfflineDecayMultiplier()),
                setting("needsDamageEnabled", settings.needsDamageEnabled()),
                setting("needs.damage.enabled", settings.needsDamageEnabled()),
                setting("needsDamageModel", settings.needsDamageModel()),
                setting("needs.damage.model", settings.needsDamageModel()),
                setting("needsDamageDualNeedRule", settings.needsDamageDualNeedRule()),
                setting("needs.damage.dualNeedRule", settings.needsDamageDualNeedRule()),
                setting("needsStarvationDamagePerMinute", settings.needsStarvationDamagePerMinute()),
                setting("needs.damage.starvationDamagePerMinute", settings.needsStarvationDamagePerMinute()),
                setting("needsDehydrationDamagePerMinute", settings.needsDehydrationDamagePerMinute()),
                setting("needs.damage.dehydrationDamagePerMinute", settings.needsDehydrationDamagePerMinute()),
                setting("needsDamageLethal", settings.needsDamageLethal()),
                setting("needs.damage.lethal", settings.needsDamageLethal()),
                setting("happinessEnabled", settings.happinessEnabled()),
                setting("happiness.enabled", settings.happinessEnabled()),
                setting("passiveBreedingEnabled", settings.passiveBreedingEnabled()),
                setting("breeding.passiveBreedingEnabled", settings.passiveBreedingEnabled()),
                setting("breeding.passiveEnabled", settings.passiveBreedingEnabled()),
                setting("breedingRequiresHappiness", settings.breedingRequiresHappiness()),
                setting("breeding.requiresHappiness", settings.breedingRequiresHappiness()),
                setting("breedingGenderEnabled", settings.breedingGenderEnabled()),
                setting("breeding.genderEnabled", settings.breedingGenderEnabled()),
                setting("traitsEnabled", settings.traitsEnabled()),
                setting("traits.enabled", settings.traitsEnabled()),
                setting("levelingEnabled", settings.levelingEnabled()),
                setting("progression.levelingEnabled", settings.levelingEnabled()),
                setting("talentsEnabled", settings.talentsEnabled()),
                setting("progression.talentsEnabled", settings.talentsEnabled()),
                setting("reviveSystemEnabled", settings.reviveSystemEnabled()),
                setting("revive.enabled", settings.reviveSystemEnabled()),
                setting("recallTeleportingEnabled", settings.recallTeleportingEnabled()),
                setting("travel.recallTeleportingEnabled", settings.recallTeleportingEnabled()),
                setting("telemetryEnabled", settings.telemetryEnabled()),
                setting("telemetry.enabled", settings.telemetryEnabled()),
                setting("telemetryBreadcrumbsEnabled", settings.telemetryBreadcrumbsEnabled()),
                setting("telemetry.breadcrumbsEnabled", settings.telemetryBreadcrumbsEnabled())
        );
    }

    private static SettingCase setting(String path, boolean value) {
        return new SettingCase(path, Boolean.toString(value));
    }

    private static SettingCase setting(String path, int value) {
        return new SettingCase(path, Integer.toString(value));
    }

    private static SettingCase setting(String path, double value) {
        return new SettingCase(path, Double.toString(value));
    }

    private static SettingCase setting(String path, String value) {
        return new SettingCase(path, "\"" + value + "\"");
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private record SettingCase(String path, String valueJson) {
    }
}
