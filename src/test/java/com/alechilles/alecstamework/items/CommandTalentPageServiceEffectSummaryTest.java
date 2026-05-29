package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandTalentPageServiceEffectSummaryTest {

    @Test
    void formatsPassiveEffectMultipliersAsPercentDeltas() throws Exception {
        CommandTalentPageService service = new CommandTalentPageService(null, null, null, null);
        Method summarizeEffects = CommandTalentPageService.class.getDeclaredMethod(
                "summarizeEffects",
                TwTalentConfig.PassiveEffect[].class
        );
        summarizeEffects.setAccessible(true);

        String summary = (String) summarizeEffects.invoke(
                service,
                (Object) new TwTalentConfig.PassiveEffect[] {
                        effect("MaxHealthMultiplier", 1.04),
                        effect("ReviveCooldownMultiplier", 0.85),
                        effect("MoveSpeedMultiplier", 1.035)
                }
        );

        assertEquals("Max Health +4%; Revive Cooldown -15%; Move Speed +3.5%", summary);
    }

    private static TwTalentConfig.PassiveEffect effect(String effectKey, double multiplier) throws Exception {
        TwTalentConfig.PassiveEffect effect = new TwTalentConfig.PassiveEffect();
        setField(effect, "effectKey", effectKey);
        setField(effect, "multiplier", multiplier);
        return effect;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
