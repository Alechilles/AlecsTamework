package com.alechilles.alecstamework.assets.patches.selftest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.assets.patches.AssetPatchDefinition;
import com.alechilles.alecstamework.assets.patches.AssetPatchTargetClassifier;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class AssetPatchSelfTestCaseTest {

    @Test
    void defaultCasesHaveValidSourcesPatchesAndClassifications() {
        for (AssetPatchSelfTestCase selfTestCase : AssetPatchSelfTestCase.defaultCases()) {
            assertFalse(selfTestCase.sourcePath().startsWith("Server/Tamework/Patches/"));
            assertTrue(selfTestCase.patchPath().startsWith("Server/Tamework/Patches/SelfTest/"));
            assertNotNull(JsonParser.parseString(selfTestCase.sourceJson("run-1")).getAsJsonObject());
            AssetPatchDefinition patch = AssetPatchDefinition.parse(
                    JsonParser.parseString(selfTestCase.patchJson("run-1")).getAsJsonObject(),
                    "SelfTest",
                    selfTestCase.patchPath()
            );
            assertTrue(patch.isEnabled());
            assertTrue(patch.getOperations().size() > 0);
            assertTrue(selfTestCase.sourcePath().equals(patch.getTarget()));
            assertTrue(AssetPatchTargetClassifier.classify(selfTestCase.sourcePath()).reloadMode()
                    == selfTestCase.expectedReloadMode());
        }
    }

    @Test
    void defaultCasesCoverRequiredFamilies() {
        String ids = AssetPatchSelfTestCase.defaultCases().stream()
                .map(AssetPatchSelfTestCase::id)
                .toList()
                .toString();

        assertTrue(ids.contains("npc-template"));
        assertTrue(ids.contains("item-action"));
        assertTrue(ids.contains("tamework-config"));
        assertTrue(ids.contains("particle-system"));
        assertTrue(ids.contains("common-asset"));
    }

    @Test
    void tameworkConfigFixtureUsesCommandItemConfigFieldNames() {
        AssetPatchSelfTestCase selfTestCase = AssetPatchSelfTestCase.defaultCases().stream()
                .filter(candidate -> candidate.id().equals("tamework-config"))
                .findFirst()
                .orElseThrow();

        JsonObject source = JsonParser.parseString(selfTestCase.sourceJson("run-1")).getAsJsonObject();
        JsonObject patch = JsonParser.parseString(selfTestCase.patchJson("run-1")).getAsJsonObject();

        assertTrue(source.has("CommandList"));
        assertFalse(source.has("Commands"));
        assertTrue(patch.toString().contains("\"Path\":\"/CommandList/0\""));
        assertTrue(patch.toString().contains("\"DisplayName\":\"Self Test\""));
    }

    @Test
    void selfTestFixturesUseServerValidatedAssetShapes() {
        JsonObject npc = source("npc-template");
        assertTrue(npc.has("MaxHealth"));
        assertTrue(npc.has("Appearance"));
        assertTrue(npc.has("NameTranslationKey"));
        assertTrue(npc.has("MotionControllerList"));
        assertTrue(npc.has("StateTransitions"));

        JsonObject item = source("item-action");
        assertFalse(item.has("RootItemInteraction"));
        assertFalse(item.has("Id"));
        assertTrue(item.has("TranslationProperties"));
        assertTrue(item.getAsJsonObject("Interactions")
                .getAsJsonObject("Primary")
                .getAsJsonArray("Interactions")
                .isEmpty());

        JsonObject itemPatch = patch("item-action");
        assertTrue(itemPatch.toString().contains("\"Path\":\"/Interactions/Primary/Interactions\""));

        JsonObject particle = source("particle-system");
        assertFalse(particle.has("Emitters"));
        assertTrue(particle.getAsJsonArray("Spawners").size() > 0);

        JsonObject particlePatch = patch("particle-system");
        assertTrue(particlePatch.toString().contains("\"Path\":\"/Spawners/0/StartDelay\""));
    }

    @Nonnull
    private static JsonObject source(@Nonnull String id) {
        return JsonParser.parseString(caseById(id).sourceJson("run-1")).getAsJsonObject();
    }

    @Nonnull
    private static JsonObject patch(@Nonnull String id) {
        return JsonParser.parseString(caseById(id).patchJson("run-1")).getAsJsonObject();
    }

    @Nonnull
    private static AssetPatchSelfTestCase caseById(@Nonnull String id) {
        return AssetPatchSelfTestCase.defaultCases().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
