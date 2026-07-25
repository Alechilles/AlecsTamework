package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CapturePolicyConfigView;
import com.alechilles.alecstamework.api.CaptureRequirementSpec;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolution;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for exact durable roll evidence freezing. */
class SpawnerCaptureResolutionFactoryTest {

    @Test
    void failedRollPreservesSignedCooldownAndPinnedFormula() {
        SpawnerCaptureResolutionFactory factory =
                new SpawnerCaptureResolutionFactory(
                        new ItemFeatureRegistry(),
                        () -> -500L
                );
        CaptureAttemptHandle attempt = new CaptureAttemptHandle(
                UUID.fromString(
                        "10000000-0000-0000-0000-000000000001"
                ),
                null,
                null,
                2,
                "source-fingerprint"
        );
        ItemFeatureConfig.CaptureItemMechanics mechanics =
                new ItemFeatureConfig.CaptureItemMechanics(
                        CaptureChanceMode.PROBABILITY,
                        3,
                        0.2D,
                        0.1D,
                        0.05D,
                        0.9D,
                        250,
                        "failure-particles",
                        "failure-sound",
                        CaptureSourceConsumption.RESOLVED_ATTEMPT,
                        CaptureSuccessDisposition.CAPTURED_ITEM,
                        null,
                        null,
                        false
                );
        CapturePolicyConfigView policy = new CapturePolicyConfigView(
                "dragon-policy",
                9L,
                2,
                Set.of("dragon"),
                2,
                0.15D,
                0.75D,
                0.4D,
                7,
                List.of(new CaptureRequirementSpec(
                        "weather",
                        "storm",
                        List.of("rain", "thunder"),
                        "{\"minimum\":2}"
                ))
        );
        SpawnerCaptureChanceService.Evaluation evaluation =
                new SpawnerCaptureChanceService.Evaluation(
                        SpawnerCaptureChanceService.Outcome.FAILED_ROLL,
                        "capture-probability-failure",
                        0.4D,
                        false,
                        0.5D,
                        0.8D
                );

        CaptureAttemptResolution result = factory.create(
                attempt,
                "Draconic_Stone",
                "dragon",
                mechanics,
                policy,
                evaluation,
                11L
        );

        assertEquals(
                CaptureAttemptResolution.Outcome.FAILED_ROLL,
                result.outcome()
        );
        assertEquals(-250L, result.failureCooldownUntilMs());
        assertEquals("Draconic_Stone", result.formula().itemConfigId());
        assertEquals("dragon-policy", result.formula().policyConfigId());
        assertEquals(9L, result.formula().policyConfigRevision());
        assertEquals(11L, result.formula().requirementGeneration());
        assertNotNull(result.formula().requirementsHash());
    }

    @Test
    void successNeverCarriesFailureCooldown() {
        SpawnerCaptureResolutionFactory factory =
                new SpawnerCaptureResolutionFactory(
                        new ItemFeatureRegistry(),
                        () -> Long.MAX_VALUE
                );
        ItemFeatureConfig.CaptureItemMechanics mechanics =
                new ItemFeatureConfig.CaptureItemMechanics(
                        CaptureChanceMode.GUARANTEED,
                        0,
                        1.0D,
                        0.0D,
                        0.0D,
                        1.0D,
                        500,
                        null,
                        null
                );
        CaptureAttemptResolution result = factory.create(
                new CaptureAttemptHandle(
                        UUID.randomUUID(),
                        null,
                        null,
                        0,
                        "source"
                ),
                "capture-device",
                "chicken",
                mechanics,
                null,
                new SpawnerCaptureChanceService.Evaluation(
                        SpawnerCaptureChanceService.Outcome.SUCCESS,
                        "capture-guaranteed-item",
                        1.0D,
                        true,
                        0.0D,
                        null
                ),
                0L
        );

        assertNull(result.failureCooldownUntilMs());
    }
}
