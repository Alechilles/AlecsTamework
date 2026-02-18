package com.alechilles.alecstamework.config.assets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests defaults for StoreHome steps and command feedback model. */
class TwCommandItemConfigStoreHomeAndFeedbackTest {

    @Test
    void storeHomeStepDefaultsToRaycastHit() {
        TwCommandItemConfig.StoreHomeStep step = new TwCommandItemConfig.StoreHomeStep();

        assertEquals(TwCommandItemConfig.StoreSource.RaycastHit, step.getSource());
    }

    @Test
    void commandFeedbackDefaultsToNullFields() {
        TwCommandItemConfig.CommandFeedback feedback = new TwCommandItemConfig.CommandFeedback();

        assertNull(feedback.getChatMessage());
        assertNull(feedback.getHudMessage());
        assertNull(feedback.getSoundEvent());
        assertNull(feedback.getParticleSystem());
        assertNull(feedback.getParticleOffset());
    }
}
