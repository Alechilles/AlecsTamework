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

        TameworkRideRiderComponent cloned = component.clone();

        assertEquals("mount-uuid", cloned.getMountUuid());
        assertTrue(cloned.isClientCameraApplied());
    }
}
