package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightHudViewModelTest {
    private static final double EPSILON = 0.0001;

    @Test
    void hudRefreshesOnlyChangedStateAfterThrottleInterval() {
        AvatarFlightHudViewModel previous = AvatarFlightHudViewModel.visible(
                0.25, 3.0, 6.0, false, "NONE");
        AvatarFlightHudViewModel changed = AvatarFlightHudViewModel.visible(
                0.5, 3.0, 6.0, false, "NONE");

        assertFalse(AvatarFlightHudSystem.shouldRefresh(null, 1_000L, changed, 1_100L, 100L));
        assertFalse(AvatarFlightHudSystem.shouldRefresh(previous, 1_000L, previous, 2_000L, 100L));
        assertFalse(AvatarFlightHudSystem.shouldRefresh(previous, 1_000L, changed, 1_099L, 100L));
        assertTrue(AvatarFlightHudSystem.shouldRefresh(previous, 1_000L, changed, 1_100L, 100L));
    }

    @Test
    void hiddenModelClearsAllRenderableValues() {
        AvatarFlightHudViewModel model = AvatarFlightHudViewModel.hidden();

        assertFalse(model.visible());
        assertEquals(0.0, model.speedRatio(), EPSILON);
        assertEquals(0.0, model.vigourCharges(), EPSILON);
        assertEquals(0.0, model.maxVigourCharges(), EPSILON);
        assertFalse(model.dimmed());
        assertEquals("NONE", model.rechargeMode());
        assertEquals(0.0, model.targetSpeedRatio(), EPSILON);
        assertEquals(0.0, model.pitchDegrees(), EPSILON);
        assertEquals("0\u00B0", model.pitchLabel());
        assertEquals(0.0, model.pipFill(0), EPSILON);
        assertFalse(model.launchChargeVisible());
        assertEquals(0.0, model.launchChargeRatio(), EPSILON);
        assertEquals(0.0, model.launchMinChargeRatio(), EPSILON);
    }

    @Test
    void partialChargesFillCurrentPipFractionally() {
        AvatarFlightHudViewModel model = AvatarFlightHudViewModel.visible(
                0.5,
                2.25,
                6.0,
                false,
                "FAST_FLIGHT"
        );

        assertEquals(1.0, model.pipFill(0), EPSILON);
        assertEquals(1.0, model.pipFill(1), EPSILON);
        assertEquals(0.25, model.pipFill(2), EPSILON);
        assertEquals(0.0, model.pipFill(3), EPSILON);
        assertEquals("FAST_FLIGHT", model.rechargeMode());
    }

    @Test
    void fullGroundedModelIsDimmed() {
        AvatarFlightHudViewModel model = AvatarFlightHudViewModel.visible(
                0.0,
                6.0,
                6.0,
                true,
                "GROUNDED"
        );

        assertTrue(model.visible());
        assertTrue(model.dimmed());
        assertEquals(1.0, model.pipFill(5), EPSILON);
    }

    @Test
    void clampingKeepsInitialSixPipAssetInBounds() {
        AvatarFlightHudViewModel high = AvatarFlightHudViewModel.visible(
                2.0,
                12.0,
                10.0,
                true,
                "grounded"
        );

        assertEquals(1.0, high.speedRatio(), EPSILON);
        assertEquals(6.0, high.vigourCharges(), EPSILON);
        assertEquals(6.0, high.maxVigourCharges(), EPSILON);
        assertEquals(1.0, high.pipFill(5), EPSILON);
        assertEquals(0.0, high.pipFill(6), EPSILON);
        assertTrue(high.dimmed());
        assertEquals("GROUNDED", high.rechargeMode());

        AvatarFlightHudViewModel low = AvatarFlightHudViewModel.visible(
                -1.0,
                -2.0,
                -4.0,
                true,
                ""
        );

        assertEquals(0.0, low.speedRatio(), EPSILON);
        assertEquals(0.0, low.vigourCharges(), EPSILON);
        assertEquals(0.0, low.maxVigourCharges(), EPSILON);
        assertEquals(0.0, low.pipFill(0), EPSILON);
        assertFalse(low.dimmed());
        assertEquals("NONE", low.rechargeMode());
    }

    @Test
    void visibleModelFormatsPitchAndClampsTargetSpeedMarker() {
        AvatarFlightHudViewModel positive = AvatarFlightHudViewModel.visible(
                0.5,
                1.4,
                Math.toRadians(30.4),
                2.25,
                6.0,
                false,
                "FAST_FLIGHT"
        );
        AvatarFlightHudViewModel negative = AvatarFlightHudViewModel.visible(
                0.5,
                0.25,
                Math.toRadians(-29.6),
                2.25,
                6.0,
                false,
                "FAST_FLIGHT"
        );
        AvatarFlightHudViewModel flat = AvatarFlightHudViewModel.visible(
                0.5,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                2.25,
                6.0,
                false,
                "FAST_FLIGHT"
        );

        assertEquals(1.0, positive.targetSpeedRatio(), EPSILON);
        assertEquals(30.4, positive.pitchDegrees(), EPSILON);
        assertEquals("+30\u00B0", positive.pitchLabel());
        assertEquals(0.25, negative.targetSpeedRatio(), EPSILON);
        assertEquals("-30\u00B0", negative.pitchLabel());
        assertEquals(0.0, flat.targetSpeedRatio(), EPSILON);
        assertEquals(0.0, flat.pitchDegrees(), EPSILON);
        assertEquals("0\u00B0", flat.pitchLabel());
    }

    @Test
    void visibleModelClampsLaunchChargeValues() {
        AvatarFlightHudViewModel model = AvatarFlightHudViewModel.visible(
                0.5,
                0.75,
                Math.toRadians(3.0),
                2.25,
                6.0,
                false,
                "FAST_FLIGHT",
                true,
                1.25,
                -0.5
        );

        assertTrue(model.launchChargeVisible());
        assertEquals(1.0, model.launchChargeRatio(), EPSILON);
        assertEquals(0.0, model.launchMinChargeRatio(), EPSILON);
    }

    @Test
    void launchChargeCanBeHiddenWhileHudRemainsVisible() {
        AvatarFlightHudViewModel model = AvatarFlightHudViewModel.visible(
                0.5,
                0.75,
                0.0,
                2.25,
                6.0,
                false,
                "FAST_FLIGHT",
                false,
                0.6,
                0.2
        );

        assertTrue(model.visible());
        assertFalse(model.launchChargeVisible());
        assertEquals(0.6, model.launchChargeRatio(), EPSILON);
        assertEquals(0.2, model.launchMinChargeRatio(), EPSILON);
    }
}
