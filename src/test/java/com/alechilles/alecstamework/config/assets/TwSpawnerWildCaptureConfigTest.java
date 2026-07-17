package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwSpawnerWildCaptureConfigTest {
    @Test
    void wildCaptureFieldsMapIntoRuntimeConfig() throws Exception {
        TwSpawnerConfig config = new TwSpawnerConfig();
        TwSpawnerConfig.CaptureSettings capture = new TwSpawnerConfig.CaptureSettings();
        setField(capture, "requireTamed", false);
        setField(capture, "tamesTarget", true);
        setField(capture, "maxHealthPercent", 20.0d);
        setField(capture, "requiredEffectId", "Tw_Status_Tranquilized");
        setField(capture, "channelAuraEffectId", "Capture_Aura");
        setField(capture, "tamedRoleOverrides", Map.of("Wild_Dragon", "Tamed_Dragon"));
        setField(config, "capture", capture);

        ItemFeatureConfig runtime = config.toItemFeatureConfig();

        assertFalse(runtime.isCaptureRequireTamed());
        assertTrue(runtime.isCaptureTamesTarget());
        assertEquals(20.0d, runtime.getCaptureMaxHealthPercent());
        assertEquals("Tw_Status_Tranquilized", runtime.getCaptureRequiredEffectId());
        assertEquals("Capture_Aura", runtime.getCaptureChannelAuraEffectId());
        assertEquals("Tamed_Dragon", runtime.resolveCaptureTamedRole("Wild_Dragon"));
    }

    @Test
    void nestedWildCaptureFieldsInheritUnlessExplicitlyReplaced() throws Exception {
        TwSpawnerConfig parent = new TwSpawnerConfig();
        TwSpawnerConfig child = new TwSpawnerConfig();
        TwSpawnerConfig.CaptureSettings parentCapture = new TwSpawnerConfig.CaptureSettings();
        TwSpawnerConfig.CaptureSettings childCapture = new TwSpawnerConfig.CaptureSettings();
        setField(parentCapture, "tamesTarget", true);
        setField(parentCapture, "maxHealthPercent", 20.0d);
        setField(parentCapture, "requiredEffectId", "Required");
        setField(parentCapture, "channelAuraEffectId", "Aura");
        setField(parentCapture, "tamedRoleOverrides", Map.of("Wild", "Tamed"));
        setField(childCapture, "maxHealthPercent", 15.0d);
        setField(parent, "capture", parentCapture);
        setField(child, "capture", childCapture);
        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Capture", Set.of("MaxHealthPercent"));

        child.inheritMissingTopLevelFrom(parent, Set.of("Capture"), nested);
        ItemFeatureConfig runtime = child.toItemFeatureConfig();

        assertTrue(runtime.isCaptureTamesTarget());
        assertEquals(15.0d, runtime.getCaptureMaxHealthPercent());
        assertEquals("Required", runtime.getCaptureRequiredEffectId());
        assertEquals("Aura", runtime.getCaptureChannelAuraEffectId());
        assertEquals(Map.of("Wild", "Tamed"), runtime.getCaptureTamedRoleOverrides());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
