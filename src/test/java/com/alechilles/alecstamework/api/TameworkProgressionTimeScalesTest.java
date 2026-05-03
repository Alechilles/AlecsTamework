package com.alechilles.alecstamework.api;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for world-scoped demo progression timing overrides. */
class TameworkProgressionTimeScalesTest {

    @Test
    void missingWorldScaleDefaultsToOne() {
        UUID worldUuid = UUID.randomUUID();
        TameworkProgressionTimeScales.clearWorldScale(worldUuid);

        assertEquals(1.0, TameworkProgressionTimeScales.getWorldScale(worldUuid));
    }

    @Test
    void registeredScaleAppliesOnlyToMatchingWorld() {
        UUID demoWorldUuid = UUID.randomUUID();
        UUID normalWorldUuid = UUID.randomUUID();
        TameworkProgressionTimeScales.clearWorldScale(demoWorldUuid);
        TameworkProgressionTimeScales.clearWorldScale(normalWorldUuid);

        TameworkProgressionTimeScales.registerWorldScale(demoWorldUuid, 30.0);

        assertEquals(30.0, TameworkProgressionTimeScales.getWorldScale(demoWorldUuid));
        assertEquals(1.0, TameworkProgressionTimeScales.getWorldScale(normalWorldUuid));

        TameworkProgressionTimeScales.clearWorldScale(demoWorldUuid);
    }

    @Test
    void invalidScalesClearToDefault() {
        UUID worldUuid = UUID.randomUUID();
        TameworkProgressionTimeScales.registerWorldScale(worldUuid, 30.0);

        TameworkProgressionTimeScales.registerWorldScale(worldUuid, Double.NaN);
        assertEquals(1.0, TameworkProgressionTimeScales.getWorldScale(worldUuid));

        TameworkProgressionTimeScales.registerWorldScale(worldUuid, 30.0);
        TameworkProgressionTimeScales.registerWorldScale(worldUuid, 0.0);
        assertEquals(1.0, TameworkProgressionTimeScales.getWorldScale(worldUuid));

        TameworkProgressionTimeScales.registerWorldScale(worldUuid, Double.POSITIVE_INFINITY);
        assertEquals(1.0, TameworkProgressionTimeScales.getWorldScale(worldUuid));
    }
}
