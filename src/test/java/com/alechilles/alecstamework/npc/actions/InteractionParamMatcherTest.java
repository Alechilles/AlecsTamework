package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.alechilles.alecstamework.npc.compat.NpcSupportTestFixture;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for Interaction parameter matching. */
class InteractionParamMatcherTest {

    @AfterEach
    void clearNpcSupport() {
        NpcSupportTestFixture.clear();
    }

    @Test
    void paramMatcherSupportsNumericOperators() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("Age", 5);
        Role role = newRoleWithScope(scope);

        InteractionParamMatcher matcher = newMatcher();

        assertTrue(matches(matcher, role, "Age", "5", TwInteractionConfig.ParamOperator.Equals));
        assertTrue(matches(matcher, role, "Age", "4", TwInteractionConfig.ParamOperator.NotEquals));
        assertTrue(matches(matcher, role, "Age", "4", TwInteractionConfig.ParamOperator.GreaterThan));
        assertTrue(matches(matcher, role, "Age", "5", TwInteractionConfig.ParamOperator.GreaterThanOrEqual));
        assertTrue(matches(matcher, role, "Age", "6", TwInteractionConfig.ParamOperator.LessThan));
        assertTrue(matches(matcher, role, "Age", "5", TwInteractionConfig.ParamOperator.LessThanOrEqual));
        assertFalse(matches(matcher, role, "Age", "6", TwInteractionConfig.ParamOperator.GreaterThan));
    }

    @Test
    void paramMatcherSupportsStringEqualsNotEquals() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("Mood", "Happy");
        Role role = newRoleWithScope(scope);

        InteractionParamMatcher matcher = newMatcher();

        assertTrue(matches(matcher, role, "Mood", "happy", TwInteractionConfig.ParamOperator.Equals));
        assertTrue(matches(matcher, role, "Mood", "Sad", TwInteractionConfig.ParamOperator.NotEquals));
        assertFalse(matches(matcher, role, "Mood", "Sad", TwInteractionConfig.ParamOperator.Equals));
    }

    private static InteractionParamMatcher newMatcher() {
        InteractionParamResolver resolver = new InteractionParamResolver(null, null, null);
        InteractionParamAccess paramAccess = new InteractionParamAccess(
                resolver,
                false,
                null,
                null,
                null,
                "LovedItems",
                "IsHarvestable",
                "IsMountable"
        );
        return new InteractionParamMatcher(paramAccess);
    }

    private static boolean matches(InteractionParamMatcher matcher,
                                   Role role,
                                   String name,
                                   String value,
                                   TwInteractionConfig.ParamOperator operator) throws Exception {
        ParamRequirement requirement = new ParamRequirement();
        setField(requirement, "name", name);
        setField(requirement, "values", new String[] { value });
        setField(requirement, "operator", operator);
        setField(requirement, "match", TwInteractionConfig.MatchType.Any);
        return matcher.matchesParamRequirement(requirement, role);
    }

    private static Role newRoleWithScope(StdScope scope) throws Exception {
        return NpcSupportTestFixture.bindRoleWithSensorScope(scope);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
