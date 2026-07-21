package com.alechilles.alecstamework.items.capturepolicy;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CapturePolicyConfigView;
import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementDecision;
import com.alechilles.alecstamework.api.CaptureRequirementPhase;
import com.alechilles.alecstamework.api.CaptureRequirementSpec;
import com.alechilles.alecstamework.api.internal.CaptureRequirementRuntime;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerCaptureChanceServiceTest {
    @Test
    void guaranteedModeBypassesPolicyRequirementsHealthAndEntropy() {
        AtomicInteger requirements = new AtomicInteger();
        AtomicInteger entropy = new AtomicInteger();
        SpawnerCaptureChanceService service = new SpawnerCaptureChanceService(runtime(requirements, true));

        SpawnerCaptureChanceService.Evaluation result = service.evaluate(
                ItemFeatureConfig.CaptureItemMechanics.GUARANTEED_DEFAULT,
                policy(10, 0.5D, 0.7D, 0.3D, null, true),
                Double.NaN, 0.0D, context(), 1L,
                () -> { entropy.incrementAndGet(); return 0.9D; }
        );

        assertEquals(SpawnerCaptureChanceService.Outcome.SUCCESS, result.outcome());
        assertTrue(result.guaranteed());
        assertEquals(0, requirements.get());
        assertEquals(0, entropy.get());
    }

    @Test
    void minimumPowerAndGuaranteedAtPowerUseNoEntropy() {
        AtomicInteger entropy = new AtomicInteger();
        SpawnerCaptureChanceService service = new SpawnerCaptureChanceService(runtime(new AtomicInteger(), true));
        ItemFeatureConfig.CaptureItemMechanics item = mechanics(4, 0.2D, 0.1D, 0.05D, 0.95D);

        assertEquals("capture-power-below-minimum", service.evaluate(
                item, policy(5, 0.0D, 1.0D, 0.0D, null, false), 5, 10, context(), 0,
                () -> { entropy.incrementAndGet(); return 0.5D; }).reason());
        assertEquals(SpawnerCaptureChanceService.Outcome.SUCCESS, service.evaluate(
                item, policy(3, 0.0D, 1.0D, 0.0D, 4, false), 5, 10, context(), 0,
                () -> { entropy.incrementAndGet(); return 0.5D; }).outcome());
        assertEquals(0, entropy.get());
    }

    @Test
    void formulaClampsHealthAndInvokesOneEntropySample() {
        AtomicInteger entropy = new AtomicInteger();
        SpawnerCaptureChanceService service = new SpawnerCaptureChanceService(runtime(new AtomicInteger(), true));
        ItemFeatureConfig.CaptureItemMechanics item = mechanics(4, 0.30D, 0.10D, 0.05D, 0.95D);

        SpawnerCaptureChanceService.Evaluation result = service.evaluate(
                item, policy(2, 0.10D, 0.5D, 0.20D, null, false),
                -5.0D, 10.0D, context(), 0L,
                () -> { entropy.incrementAndGet(); return 0.25D; }
        );

        // ((.30 + 2*.10 + .20*1) - .10) * .5 = .30
        assertEquals(0.30D, result.effectiveChance(), 0.000001D);
        assertEquals(1.0D, result.missingHealthFraction());
        assertEquals(SpawnerCaptureChanceService.Outcome.SUCCESS, result.outcome());
        assertEquals(1, entropy.get());
    }

    @Test
    void requirementDenialFailsClosedBeforeEntropy() {
        AtomicInteger entropy = new AtomicInteger();
        SpawnerCaptureChanceService service = new SpawnerCaptureChanceService(runtime(new AtomicInteger(), false));

        SpawnerCaptureChanceService.Evaluation result = service.evaluate(
                mechanics(1, 0.5D, 0.0D, 0.0D, 1.0D),
                policy(0, 0.0D, 1.0D, 0.0D, null, true),
                5, 10, context(), 0,
                () -> { entropy.incrementAndGet(); return 0.0D; }
        );

        assertFalse(result.outcome() == SpawnerCaptureChanceService.Outcome.SUCCESS);
        assertEquals("blocked", result.reason());
        assertEquals(0, entropy.get());
    }

    private static ItemFeatureConfig.CaptureItemMechanics mechanics(
            int power, double base, double perPower, double minimum, double maximum
    ) {
        return new ItemFeatureConfig.CaptureItemMechanics(
                CaptureChanceMode.PROBABILITY, power, base, perPower, minimum, maximum, 0, null, null
        );
    }

    private static CapturePolicyConfigView policy(int minimumPower,
                                                   double resistance,
                                                   double multiplier,
                                                   double healthBonus,
                                                   Integer guaranteedAt,
                                                   boolean requirement) {
        return new CapturePolicyConfigView(
                "policy", 1L, 0, Set.of("Hydra"), minimumPower, resistance, multiplier,
                healthBonus, guaranteedAt,
                requirement ? List.of(new CaptureRequirementSpec("hydragon:ready", null, List.of(), null)) : List.of()
        );
    }

    private static CaptureRequirementRuntime runtime(AtomicInteger calls, boolean allow) {
        return new CaptureRequirementRuntime() {
            @Override public long captureRequirementGeneration() { return 0; }
            @Override public CaptureRequirementDecision evaluateCaptureRequirement(
                    CaptureRequirementSpec spec, CaptureRequirementContext context, long expectedGeneration) {
                calls.incrementAndGet();
                return allow ? CaptureRequirementDecision.allow() : CaptureRequirementDecision.deny("blocked");
            }
        };
    }

    private static CaptureRequirementContext context() {
        return new CaptureRequirementContext(
                UUID.randomUUID(), CaptureRequirementPhase.FINAL_REVALIDATION, UUID.randomUUID(), UUID.randomUUID(),
                null, "Hydra", "default", "Draconic_Stone", 0.5D,
                CaptureRequirementContext.UNKNOWN_PROFILE_REVISION
        );
    }
}
