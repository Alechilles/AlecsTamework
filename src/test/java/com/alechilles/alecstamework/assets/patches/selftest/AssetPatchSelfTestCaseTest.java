package com.alechilles.alecstamework.assets.patches.selftest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(ids.contains("common-restart-required"));
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
}
