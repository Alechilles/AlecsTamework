package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.CapturePolicyConfigView;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyIndex;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwCapturePolicyConfigTest {
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
