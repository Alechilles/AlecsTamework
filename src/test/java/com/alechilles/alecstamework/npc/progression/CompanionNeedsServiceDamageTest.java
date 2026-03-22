package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** Protects starvation/dehydration damage behavior for needs progression. */
class CompanionNeedsServiceDamageTest {
    private static final long ONE_MINUTE_MS = 60_000L;

    @Test
    void minOnlyDamageTriggersOnlyWhenNeedIsAtMin() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();

        double noMinDamage = CompanionNeedsService.resolveNeedsDamageAmount(config, values, 10.0, 10.0, ONE_MINUTE_MS);
        double hungerMinDamage = CompanionNeedsService.resolveNeedsDamageAmount(config, values, 0.0, 10.0, ONE_MINUTE_MS);

        assertEquals(0.0, noMinDamage, 0.000001);
        assertEquals(2.0, hungerMinDamage, 0.000001);
    }

    @Test
    void dualMinUsesHigherOnlyByDefault() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();

        double dualMinDamage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                0.0,
                2L * ONE_MINUTE_MS
        );

        assertEquals(6.0, dualMinDamage, 0.000001);
    }

    @Test
    void damageDisabledByDefaultKeepsLegacyNoDamageBehavior() throws Exception {
        TwNeedsConfig config = createConfig();
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();

        double damage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                0.0,
                ONE_MINUTE_MS
        );

        assertEquals(0.0, damage, 0.000001);
    }

    @Test
    void nonLethalModeCapsDamageToLeaveOneHealthPoint() {
        assertEquals(
                5.0f,
                CompanionNeedsService.resolveAppliedDamageAmountFromHealth(5.0, true, 2.0),
                0.000001
        );
        assertEquals(
                1.0f,
                CompanionNeedsService.resolveAppliedDamageAmountFromHealth(5.0, false, 2.0),
                0.000001
        );
        assertEquals(
                0.0f,
                CompanionNeedsService.resolveAppliedDamageAmountFromHealth(1.0, false, 0.5),
                0.000001
        );
    }

    private TwNeedsConfig createConfigWithDamageEnabled() throws Exception {
        TwNeedsConfig config = createConfig();
        TwNeedsConfig.DamageSettings damage = new TwNeedsConfig.DamageSettings();
        setField(damage, "enabled", true);
        setField(config, "damage", damage);
        return config;
    }

    private TwNeedsConfig createConfig() throws Exception {
        Constructor<TwNeedsConfig> constructor = TwNeedsConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
