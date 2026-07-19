package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Protects active model-trail state across ECS component cloning. */
class AvatarFlightTrailComponentStateTest {

    @Test
    void clonePreservesActiveTrailState() {
        AvatarFlightComponent component = new AvatarFlightComponent("Dragon", 1L);
        component.setFastGlideTrailChainId(1);
        component.setActiveTrailRootInteraction("Root_Dragon_Trail_Flap");
        component.setBurstTrailUntilMs(123456L);

        AvatarFlightComponent clone = component.clone();

        assertEquals(1, clone.getFastGlideTrailChainId());
        assertEquals("Root_Dragon_Trail_Flap", clone.getActiveTrailRootInteraction());
        assertEquals(123456L, clone.getBurstTrailUntilMs());
    }
}
