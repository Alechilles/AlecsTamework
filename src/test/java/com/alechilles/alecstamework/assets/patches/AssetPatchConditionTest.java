package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class AssetPatchConditionTest {

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

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
