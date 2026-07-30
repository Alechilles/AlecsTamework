package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwCompanionFlightToggleSettings;
import com.alechilles.alecstamework.npc.movement.MotionControllerTameworkFly;
import com.alechilles.alecstamework.npc.movement.MotionControllerTameworkMountedGlide;
import com.alechilles.alecstamework.npc.movement.MotionControllerTameworkRideWalk;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Regression coverage for recognized companion flight controller families. */
class BondedCompanionFlightModeReaderTest {
    @Test
    void classifiesNativeAndTameworkFlightControllers() {
        assertEquals(Optional.of(true), BondedCompanionFlightModeReader
                .classify(MotionControllerFly.class));
        assertEquals(Optional.of(true), BondedCompanionFlightModeReader
                .classify(MotionControllerTameworkFly.class));
        assertEquals(Optional.of(true), BondedCompanionFlightModeReader
                .classify(MotionControllerTameworkMountedGlide.class));
    }

    @Test
    void classifiesNativeAndTameworkGroundControllers() {
        assertEquals(Optional.of(false), BondedCompanionFlightModeReader
                .classify(MotionControllerWalk.class));
        assertEquals(Optional.of(false), BondedCompanionFlightModeReader
                .classify(MotionControllerTameworkRideWalk.class));
    }

    @Test
    void missingOrUnknownControllerIsUnavailable() {
        assertTrue(BondedCompanionFlightModeReader.classify(null).isEmpty());
        assertTrue(BondedCompanionFlightModeReader.classify(Object.class)
                .isEmpty());
    }

    @Test
    void disabledCapabilityIsUnavailableBeforeReadingLiveRole() {
        assertTrue(new BondedCompanionFlightModeReader().read(null,
                new TwCompanionFlightToggleSettings()).isEmpty());
    }

    @Test
    void incompleteCapabilityIsUnavailableBeforeReadingLiveRole()
            throws Exception {
        TwCompanionFlightToggleSettings settings =
                new TwCompanionFlightToggleSettings();
        Field enabled = TwCompanionFlightToggleSettings.class
                .getDeclaredField("enabled");
        enabled.setAccessible(true);
        enabled.setBoolean(settings, true);

        assertTrue(new BondedCompanionFlightModeReader().read(null, settings)
                .isEmpty());
    }
}
