package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TwHappinessConfigInheritanceTest {
    @Test
    void partialDispositionAndExplicitFalseFoodPolicyKeepTheirChildValues() throws Exception {
        TwHappinessConfig parent = new TwHappinessConfig();
        TwHappinessConfig child = new TwHappinessConfig();
        TwHappinessConfig.DispositionSettings parentDisposition = new TwHappinessConfig.DispositionSettings();
        TwHappinessConfig.DispositionSettings childDisposition = new TwHappinessConfig.DispositionSettings();
        TwHappinessConfig.ImpulseSettings parentImpulses = new TwHappinessConfig.ImpulseSettings();
        TwHappinessConfig.ImpulseSettings childImpulses = new TwHappinessConfig.ImpulseSettings();

        setField(parentDisposition, "mode", "FLAT");
        setField(parentDisposition, "traitMin", 0.7d);
        setField(parentDisposition, "traitMax", 1.4d);
        setField(childDisposition, "mode", "MULTIPLIER");
        setField(parentImpulses, "singleFoodEffect", true);
        setField(childImpulses, "singleFoodEffect", false);
        setField(parent, "disposition", parentDisposition);
        setField(child, "disposition", childDisposition);
        setField(parent, "impulses", parentImpulses);
        setField(child, "impulses", childImpulses);

        child.inheritMissingTopLevelFrom(parent, Set.of("Disposition", "Impulses"), Map.of(
                "Disposition", Set.of("Mode"),
                "Impulses", Set.of("SingleFoodEffect")
        ));

        assertEquals(TwHappinessConfig.DispositionMode.MULTIPLIER, child.getDisposition().getMode());
        assertEquals(0.7d, child.getDisposition().getTraitMin(), 0.000001d);
        assertEquals(1.4d, child.getDisposition().getTraitMax(), 0.000001d);
        assertFalse(child.getImpulses().isSingleFoodEffect());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
