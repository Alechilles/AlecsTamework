package com.alechilles.alecstamework.config.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Covers defaults, sanitization, and inheritance behavior for TickPolicy and Damage sections. */
class TwNeedsConfigTickPolicyDamageSettingsTest {

    @Test
    void defaultsExposeExpectedTickPolicyAndDamageValues() {
        TwNeedsConfig.TickPolicySettings tickPolicy = new TwNeedsConfig.TickPolicySettings();
        TwNeedsConfig.DamageSettings damage = new TwNeedsConfig.DamageSettings();

        assertEquals(TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY, tickPolicy.getMode());
        assertEquals(72.0, tickPolicy.getOwnerOfflineGraceHours(), 0.000001);
        assertEquals(1.0, tickPolicy.getOwnerOfflineDecayMultiplier(), 0.000001);

        assertFalse(damage.isEnabled());
        assertEquals(TwNeedsConfig.DamageModel.MIN_ONLY_FLAT, damage.getModel());
        assertEquals(TwNeedsConfig.DualNeedRule.USE_HIGHER_ONLY, damage.getDualNeedRule());
        assertEquals(2.0, damage.getStarvationDamagePerMinute(), 0.000001);
        assertEquals(3.0, damage.getDehydrationDamagePerMinute(), 0.000001);
        assertTrue(damage.isLethal());
    }

    @Test
    void invalidValuesFallbackToSafeDefaults() throws Exception {
        TwNeedsConfig.TickPolicySettings tickPolicy = new TwNeedsConfig.TickPolicySettings();
        setField(tickPolicy, "ownerOfflineGraceHours", -5.0d);
        setField(tickPolicy, "ownerOfflineDecayMultiplier", Double.NaN);

        TwNeedsConfig.DamageSettings damage = new TwNeedsConfig.DamageSettings();
        setField(damage, "model", null);
        setField(damage, "dualNeedRule", null);
        setField(damage, "starvationDamagePerMinute", -1.0d);
        setField(damage, "dehydrationDamagePerMinute", Double.NaN);

        assertEquals(72.0, tickPolicy.getOwnerOfflineGraceHours(), 0.000001);
        assertEquals(1.0, tickPolicy.getOwnerOfflineDecayMultiplier(), 0.000001);
        assertEquals(TwNeedsConfig.DamageModel.MIN_ONLY_FLAT, damage.getModel());
        assertEquals(TwNeedsConfig.DualNeedRule.USE_HIGHER_ONLY, damage.getDualNeedRule());
        assertEquals(2.0, damage.getStarvationDamagePerMinute(), 0.000001);
        assertEquals(3.0, damage.getDehydrationDamagePerMinute(), 0.000001);
    }

    @Test
    void inheritanceCopiesTickPolicyAndDamageWhenNotExplicit() throws Exception {
        TwNeedsConfig parent = new TwNeedsConfig();
        TwNeedsConfig child = new TwNeedsConfig();

        TwNeedsConfig.TickPolicySettings parentTickPolicy = new TwNeedsConfig.TickPolicySettings();
        setField(parentTickPolicy, "mode", TwNeedsConfig.TickPolicyMode.ANY_LOADED_PLAYER);
        TwNeedsConfig.DamageSettings parentDamage = new TwNeedsConfig.DamageSettings();
        setField(parentDamage, "enabled", true);

        setField(parent, "tickPolicy", parentTickPolicy);
        setField(parent, "damage", parentDamage);

        child.inheritMissingTopLevelFrom(parent, Set.of("Enabled"));

        assertSame(parentTickPolicy, child.getTickPolicy());
        assertSame(parentDamage, child.getDamage());
    }

    @Test
    void inheritancePreservesExplicitTickPolicyAndDamage() throws Exception {
        TwNeedsConfig parent = new TwNeedsConfig();
        TwNeedsConfig child = new TwNeedsConfig();

        TwNeedsConfig.TickPolicySettings parentTickPolicy = new TwNeedsConfig.TickPolicySettings();
        setField(parentTickPolicy, "mode", TwNeedsConfig.TickPolicyMode.ANY_LOADED_PLAYER);
        TwNeedsConfig.DamageSettings parentDamage = new TwNeedsConfig.DamageSettings();
        setField(parentDamage, "enabled", true);

        TwNeedsConfig.TickPolicySettings childTickPolicy = new TwNeedsConfig.TickPolicySettings();
        setField(childTickPolicy, "mode", TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY);
        TwNeedsConfig.DamageSettings childDamage = new TwNeedsConfig.DamageSettings();
        setField(childDamage, "enabled", false);

        setField(parent, "tickPolicy", parentTickPolicy);
        setField(parent, "damage", parentDamage);
        setField(child, "tickPolicy", childTickPolicy);
        setField(child, "damage", childDamage);

        child.inheritMissingTopLevelFrom(parent, Set.of("TickPolicy", "Damage"));

        assertSame(childTickPolicy, child.getTickPolicy());
        assertSame(childDamage, child.getDamage());
    }

    @Test
    void inheritanceMergesMissingNestedFieldsWhenTickPolicyAndDamageAreExplicit() throws Exception {
        TwNeedsConfig parent = new TwNeedsConfig();
        TwNeedsConfig child = new TwNeedsConfig();

        TwNeedsConfig.TickPolicySettings parentTickPolicy = new TwNeedsConfig.TickPolicySettings();
        setField(parentTickPolicy, "mode", TwNeedsConfig.TickPolicyMode.ANY_LOADED_PLAYER);
        setField(parentTickPolicy, "ownerOfflineGraceHours", 120.0d);
        TwNeedsConfig.DamageSettings parentDamage = new TwNeedsConfig.DamageSettings();
        setField(parentDamage, "enabled", true);
        setField(parentDamage, "starvationDamagePerMinute", 9.0d);

        TwNeedsConfig.TickPolicySettings childTickPolicy = new TwNeedsConfig.TickPolicySettings();
        setField(childTickPolicy, "mode", TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY);
        setField(childTickPolicy, "ownerOfflineGraceHours", 5.0d);
        TwNeedsConfig.DamageSettings childDamage = new TwNeedsConfig.DamageSettings();
        setField(childDamage, "enabled", false);
        setField(childDamage, "starvationDamagePerMinute", 1.0d);

        setField(parent, "tickPolicy", parentTickPolicy);
        setField(parent, "damage", parentDamage);
        setField(child, "tickPolicy", childTickPolicy);
        setField(child, "damage", childDamage);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("TickPolicy", Set.of("Mode"));
        nested.put("Damage", Set.of("Enabled"));
        child.inheritMissingTopLevelFrom(parent, Set.of("TickPolicy", "Damage"), nested);

        assertEquals(TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY, child.getTickPolicy().getMode());
        assertEquals(120.0d, child.getTickPolicy().getOwnerOfflineGraceHours(), 0.000001);
        assertFalse(child.getDamage().isEnabled());
        assertEquals(9.0d, child.getDamage().getStarvationDamagePerMinute(), 0.000001);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
