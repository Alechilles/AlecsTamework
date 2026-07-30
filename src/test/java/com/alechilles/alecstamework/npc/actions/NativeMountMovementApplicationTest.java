package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwCompanionMovementConfig;
import com.alechilles.alecstamework.npc.progression.CompanionMovementSpeedResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression coverage for the multiplier handed to native rider movement settings. */
class NativeMountMovementApplicationTest {

    @Test
    void passesExactClampedMultiplierToNativeRiderSettings() throws Exception {
        CompanionMovementSpeedResolver.Result resolved = new CompanionMovementSpeedResolver().resolve(
                new TwCompanionMovementConfig.ResolvedMovement("test:cow", 1.0, 0.5, 2.0, List.of()),
                Map.of(),
                1.024
        );

        assertEquals(1.024, NativeMountMovementApplication.selectNativeMountMultiplier(resolved), 0.0000001);
    }
}
