package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.CapturePolicyConfigView;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyIndex;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwCapturePolicyConfigTest {
    @Test
    void codecDecodesCompletePolicyShapeAndExplicitZeroes() throws Exception {
        TwCapturePolicyConfig config = decode("HydragonHydra", """
                {
                  "Enabled": true,
                  "Priority": 100,
                  "RoleIds": ["Hydra"],
                  "Difficulty": {
                    "MinimumPower": 0,
                    "Resistance": 0.0,
                    "ChanceMultiplier": 0.7,
                    "MissingHealthBonus": 0.25,
                    "GuaranteedAtPower": 5
                  },
                  "Requirements": [
                    {
                      "Id": "hydragon:special_encounter_capture_ready",
                      "Param": "grounded_phase",
                      "Values": ["grounded"],
                      "JsonPayload": "true"
                    }
                  ]
                }
                """);

        config.validateOrThrow();

        assertTrue(config.isEnabled());
        assertEquals(100, config.getPriority());
        assertArrayEquals(new String[] { "Hydra" }, config.getRoleIds());
        assertEquals(0, config.getDifficulty().getMinimumPower());
        assertEquals(0.0D, config.getDifficulty().getResistance());
        assertEquals(0.7D, config.getDifficulty().getChanceMultiplier());
        assertEquals(0.25D, config.getDifficulty().getMissingHealthBonus());
        assertEquals(5, config.getDifficulty().getGuaranteedAtPower());
        assertEquals("hydragon:special_encounter_capture_ready", config.getRequirements()[0].getId());
        assertEquals("grounded_phase", config.getRequirements()[0].getParam());
        assertArrayEquals(new String[] { "grounded" }, config.getRequirements()[0].getValues());
        assertEquals("true", config.getRequirements()[0].getJsonPayload());
    }

    @Test
    void decodedInvalidPolicyFailsValidationWithoutClamping() throws Exception {
        TwCapturePolicyConfig negative = decode("NegativePolicy", """
                {
                  "RoleIds": ["Hydra"],
                  "Difficulty": {
                    "MinimumPower": -1,
                    "ChanceMultiplier": -0.5
                  }
                }
                """);

        assertEquals(-1, negative.getDifficulty().getMinimumPower());
        assertEquals(-0.5D, negative.getDifficulty().getChanceMultiplier());
        assertThrows(IllegalArgumentException.class, negative::validateOrThrow);
        assertThrows(RuntimeException.class, () -> TwCapturePolicyConfig.CODEC.decode(
                BsonDocument.parse("{\"RoleIds\":[\"Hydra\"],\"Difficulty\":{\"MinimumPower\":\"three\"}}"),
                new ExtraInfo()
        ));
    }

    @Test
    void nestedDifficultyInheritsAndRequirementsReplace() throws Exception {
        TwCapturePolicyConfig parent = policy("Parent", 1, "Hydra");
        TwCapturePolicyConfig child = policy("Child", 2, "Hydra");
        Object parentDifficulty = field(parent, "difficulty");
        Object childDifficulty = field(child, "difficulty");
        set(parentDifficulty, "minimumPower", 3);
        set(parentDifficulty, "resistance", 0.2D);
        set(parentDifficulty, "chanceMultiplier", 0.7D);
        set(childDifficulty, "resistance", 0.1D);
        Object parentRequirement = requirement("hydragon:ready");
        Object childRequirement = requirement("hydragon:other");
        set(parent, "requirements", arrayOf(parentRequirement));
        set(child, "requirements", arrayOf(childRequirement));

        child.inheritMissingTopLevelFrom(parent, Set.of("Difficulty", "Requirements"),
                Map.of("Difficulty", Set.of("Resistance")));
        CapturePolicyConfigView view = child.toView(8L);

        assertEquals(3, view.minimumPower());
        assertEquals(0.1D, view.resistance());
        assertEquals(0.7D, view.chanceMultiplier());
        assertEquals(List.of("hydragon:other"), view.requirements().stream().map(value -> value.id()).toList());
    }

    @Test
    void deterministicWinnerUsesPriorityThenCaseInsensitiveAndCaseSensitiveId() throws Exception {
        TwCapturePolicyConfig low = policy("zeta", 1, "Hydra");
        TwCapturePolicyConfig upper = policy("Alpha", 2, "Hydra");
        TwCapturePolicyConfig lower = policy("alpha", 2, "Hydra");

        CapturePolicyIndex index = CapturePolicyIndex.compile(List.of(low, lower, upper), 4L);

        assertEquals("Alpha", index.resolveForRole("Hydra").orElseThrow().configId());
    }

    @Test
    void invalidUpdateRetainsLastValidIndex() throws Exception {
        CapturePolicyRegistry registry = new CapturePolicyRegistry();
        assertTrue(registry.replace(List.of(policy("valid", 0, "Hydra")), 1L).applied());
        TwCapturePolicyConfig invalid = policy("invalid", 0);

        CapturePolicyRegistry.ReloadResult result = registry.replace(List.of(invalid), 2L);

        assertFalse(result.applied());
        assertEquals(1L, result.active().revision());
        assertTrue(result.active().resolveForRole("Hydra").isPresent());
    }

    @Test
    void rejectsMalformedRequirementAndDifficulty() throws Exception {
        TwCapturePolicyConfig config = policy("bad", 0, "Hydra");
        Object requirement = requirement("not_namespaced");
        set(config, "requirements", arrayOf(requirement));
        assertThrows(IllegalArgumentException.class, config::validateOrThrow);

        set(config, "requirements", java.lang.reflect.Array.newInstance(requirement.getClass(), 0));
        set(field(config, "difficulty"), "chanceMultiplier", Double.NaN);
        assertThrows(IllegalArgumentException.class, config::validateOrThrow);
    }

    private static TwCapturePolicyConfig policy(String id, int priority, String... roles) throws Exception {
        TwCapturePolicyConfig config = new TwCapturePolicyConfig();
        set(config, "id", id);
        set(config, "priority", priority);
        set(config, "roleIds", roles);
        return config;
    }

    private static TwCapturePolicyConfig decode(String id, String json) throws Exception {
        TwCapturePolicyConfig config = TwCapturePolicyConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
        set(config, "id", id);
        return config;
    }

    private static Object requirement(String id) throws Exception {
        Object value = Class.forName(TwCapturePolicyConfig.RequirementSettings.class.getName())
                .getDeclaredConstructor().newInstance();
        set(value, "id", id);
        return value;
    }

    private static Object arrayOf(Object value) {
        Object array = java.lang.reflect.Array.newInstance(value.getClass(), 1);
        java.lang.reflect.Array.set(array, 0, value);
        return array;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
