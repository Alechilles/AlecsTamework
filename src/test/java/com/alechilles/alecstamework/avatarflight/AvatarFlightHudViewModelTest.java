package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightHudViewModelTest {
    private static final double EPSILON = 0.0001;

    @Test
    void hiddenModelClearsAllRenderableValues() {
        AvatarFlightHudViewModel model = AvatarFlightHudViewModel.hidden();

        assertFalse(model.visible());
        assertEquals(0.0, model.speedRatio(), EPSILON);
        assertEquals(0.0, model.vigourCharges(), EPSILON);
        assertEquals(0.0, model.maxVigourCharges(), EPSILON);
        assertFalse(model.dimmed());
        assertEquals("NONE", model.rechargeMode());
        assertEquals(0.0, model.pipFill(0), EPSILON);
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
}
