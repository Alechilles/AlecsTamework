package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.bson.BsonDocument;

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

    @Test
    void chanceMechanicsDefaultGuaranteedAndNestedFieldsInheritExactly() throws Exception {
        TwSpawnerConfig parent = new TwSpawnerConfig();
        TwSpawnerConfig child = new TwSpawnerConfig();
        TwSpawnerConfig.CaptureSettings parentCapture = new TwSpawnerConfig.CaptureSettings();
        TwSpawnerConfig.CaptureSettings childCapture = new TwSpawnerConfig.CaptureSettings();
        setField(parentCapture, "chanceMode", CaptureChanceMode.PROBABILITY);
        setField(parentCapture, "power", 5);
        setField(parentCapture, "baseChance", 0.4D);
        setField(parentCapture, "chancePerPower", 0.1D);
        setField(parentCapture, "minimumChance", 0.05D);
        setField(parentCapture, "maximumChance", 0.95D);
        setField(parentCapture, "failureCooldownMs", 2500);
        setField(childCapture, "power", 0);
        setField(childCapture, "chanceMode", CaptureChanceMode.GUARANTEED);
        setField(parent, "capture", parentCapture);
        setField(child, "capture", childCapture);

        child.inheritMissingTopLevelFrom(parent, Set.of("Capture"),
                Map.of("Capture", Set.of("Power", "ChanceMode")));
        ItemFeatureConfig.CaptureItemMechanics mechanics = child.toItemFeatureConfig().getCaptureMechanics();

        assertEquals(CaptureChanceMode.GUARANTEED, mechanics.chanceMode());
        assertEquals(0, mechanics.power());
        assertEquals(0.4D, mechanics.baseChance());
        assertEquals(0.1D, mechanics.chancePerPower());
        assertEquals(2500, mechanics.failureCooldownMs());
        assertEquals(CaptureChanceMode.GUARANTEED,
                new TwSpawnerConfig().toItemFeatureConfig().getCaptureMechanics().chanceMode());
    }

    @Test
    void capturedItemDispositionAndResolvedSourceConsumptionDecode() {
        TwSpawnerConfig config = TwSpawnerConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "Capture": {
                    "SourceConsumption": "ResolvedAttempt",
                    "SuccessDisposition": "CapturedItem"
                  }
                }
                """), new ExtraInfo());
        ItemFeatureConfig.CaptureItemMechanics mechanics =
                config.toItemFeatureConfig().getCaptureMechanics();

        assertEquals(CaptureSourceConsumption.RESOLVED_ATTEMPT, mechanics.sourceConsumption());
        assertEquals(CaptureSuccessDisposition.CAPTURED_ITEM,
                mechanics.successDisposition());
    }

    @Test
    void captureDispositionDefaultsToCapturedItemBehavior() {
        ItemFeatureConfig.CaptureItemMechanics mechanics =
                new TwSpawnerConfig().toItemFeatureConfig().getCaptureMechanics();

        assertEquals(CaptureSourceConsumption.SUCCESS_ONLY, mechanics.sourceConsumption());
        assertEquals(CaptureSuccessDisposition.CAPTURED_ITEM, mechanics.successDisposition());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
