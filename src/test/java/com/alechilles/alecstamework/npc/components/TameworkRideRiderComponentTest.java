package com.alechilles.alecstamework.npc.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests rider-side Tamework ride component linkage behavior. */
class TameworkRideRiderComponentTest {

    @Test
    void sanitizesBlankMountUuid() {
        TameworkRideRiderComponent component = new TameworkRideRiderComponent();

        component.setMountUuid(" ");

        assertEquals("", component.getMountUuid());
    }

    @Test
    void clonePreservesMountUuidAndCameraState() {
        TameworkRideRiderComponent component = new TameworkRideRiderComponent("mount-uuid");
        component.setClientCameraApplied(true);
        component.setClientSpeedModifier(12.5);

        TameworkRideRiderComponent cloned = component.clone();

        assertEquals("mount-uuid", cloned.getMountUuid());
        assertTrue(cloned.isClientCameraApplied());
        assertEquals(12.5, cloned.getClientSpeedModifier());
    }

    @Test
    void sanitizesInvalidClientSpeedModifier() {
        TameworkRideRiderComponent component = new TameworkRideRiderComponent("mount-uuid");

        component.setClientSpeedModifier(0.0);

        assertEquals(-1.0, component.getClientSpeedModifier());
    }
}
