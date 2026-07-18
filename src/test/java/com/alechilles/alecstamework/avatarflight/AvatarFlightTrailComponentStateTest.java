package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Protects cancellable sustained-trail state across ECS component cloning. */
class AvatarFlightTrailComponentStateTest {

    @Test
    void clonePreservesFastGlideInteractionChainId() {
        AvatarFlightComponent component = new AvatarFlightComponent("Dragon", 1L);
        component.setFastGlideTrailChainId(-17);

        AvatarFlightComponent clone = component.clone();

        assertEquals(-17, clone.getFastGlideTrailChainId());
    }
}
