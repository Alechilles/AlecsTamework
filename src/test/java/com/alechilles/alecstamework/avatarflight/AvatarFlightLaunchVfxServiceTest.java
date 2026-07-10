package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightVfxSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightLaunchVfxServiceTest {
    private static final double EPSILON = 0.00001;

    @Test
    void firstGroundedChargeTickEmitsAndSchedulesWithoutEarlyRepeat() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchVfxService service = new AvatarFlightLaunchVfxService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        AvatarFlightInputComponent input = chargingInput(1000L, true);
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        TransformComponent transform = transform(1.0, 2.0, 3.0);

        service.tick(flight, input, input(0L, true), output(false), config, transform, 1000L, null, null);
        service.tick(flight, input, input(0L, true), output(false), config, transform, 1500L, null, null);

        assertEquals(1, sink.emissions.size());
        Emission emission = sink.emissions.getFirst();
        assertEquals(AvatarFlightVfxSettings.DEFAULT_CHARGE_SYSTEM, emission.systemId());
        assertEquals(1.0, emission.x(), EPSILON);
        assertEquals(2.05, emission.y(), EPSILON);
        assertEquals(3.0, emission.z(), EPSILON);
        assertEquals(1600L, flight.getNextLaunchChargeVfxAtMs());
    }

    @Test
    void airborneHoldStopsPulsesAndPreservesGroundOrigin() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchVfxService service = new AvatarFlightLaunchVfxService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        AvatarFlightInputComponent input = chargingInput(1000L, true);
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

        service.tick(flight, input, input(0L, true), output(false), config,
                transform(4.0, 5.0, 6.0), 1000L, null, null);
        input.setOnGround(false);
        service.tick(flight, input, input(0L, false), output(false), config,
                transform(40.0, 50.0, 60.0), 2000L, null, null);

        assertEquals(1, sink.emissions.size());
        assertTrue(flight.isLaunchVfxOriginValid());
        assertEquals(4.0, flight.getLaunchVfxOriginX(), EPSILON);
        assertEquals(5.05, flight.getLaunchVfxOriginY(), EPSILON);
    }

    @Test
    void rejectedReleaseEmitsOneCancelAtStoredGroundOrigin() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchVfxService service = new AvatarFlightLaunchVfxService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.captureLaunchVfxOrigin(7.0, 8.0, 9.0, 0.5);

        service.tick(flight, new AvatarFlightInputComponent(), input(200L, true), output(false),
                TwAvatarFlightConfig.defaultConfig(), transform(70.0, 80.0, 90.0), 2000L, null, null);

        assertEquals(1, sink.emissions.size());
        Emission emission = sink.emissions.getFirst();
        assertEquals(AvatarFlightVfxSettings.DEFAULT_CANCEL_SYSTEM, emission.systemId());
        assertEquals(7.0, emission.x(), EPSILON);
        assertEquals(8.0, emission.y(), EPSILON);
        assertFalse(flight.isLaunchVfxOriginValid());
    }

    @Test
    void successfulReleaseSelectsOneTierAndClearsState() throws Exception {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchVfxService service = new AvatarFlightLaunchVfxService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.captureLaunchVfxOrigin(1.0, 2.0, 3.0, 0.0);
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setField(config.getLaunch(), "minChargeMs", 0.0);
        setField(config.getLaunch(), "maxChargeMs", 1000.0);
        setField(config.getLaunch(), "chargeExponent", 1.0);

        service.tick(flight, new AvatarFlightInputComponent(), input(800L, true), output(true),
                config, transform(20.0, 30.0, 40.0), 2000L, null, null);

        assertEquals(1, sink.emissions.size());
        assertEquals(AvatarFlightVfxSettings.DEFAULT_FULL_SYSTEM, sink.emissions.getFirst().systemId());
        assertEquals(1.2f, sink.emissions.getFirst().scale(), 0.00001f);
        assertFalse(flight.isLaunchVfxOriginValid());
        assertEquals(0L, flight.getNextLaunchChargeVfxAtMs());
    }

    @Test
    void disabledVfxClearsStateWithoutEmission() throws Exception {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchVfxService service = new AvatarFlightLaunchVfxService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.captureLaunchVfxOrigin(1.0, 2.0, 3.0, 0.0);
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setField(config.getVfx(), "enabled", false);

        service.tick(flight, chargingInput(1000L, true), input(0L, true), output(false),
                config, transform(1.0, 2.0, 3.0), 1000L, null, null);

        assertTrue(sink.emissions.isEmpty());
        assertFalse(flight.isLaunchVfxOriginValid());
    }

    @Test
    void ownerReferenceIsForwardedToEmissionSink() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchVfxService service = new AvatarFlightLaunchVfxService(sink);
        Ref<EntityStore> ownerRef = new Ref<>(null, 7);

        service.tick(new AvatarFlightComponent(), chargingInput(1000L, true), input(0L, true),
                output(false), TwAvatarFlightConfig.defaultConfig(), transform(1.0, 2.0, 3.0),
                1000L, ownerRef, null);

        assertEquals(1, sink.emissions.size());
        assertSame(ownerRef, sink.emissions.getFirst().ownerRef());
    }

    private static AvatarFlightInputComponent chargingInput(long startedAtMs, boolean onGround) {
        AvatarFlightInputComponent input = new AvatarFlightInputComponent();
        input.beginLaunchCharge(startedAtMs);
        input.setOnGround(onGround);
        return input;
    }

    private static AvatarFlightController.Input input(long launchHoldMs, boolean onGround) {
        return new AvatarFlightController.Input(
                0.0, 0.0, 0.0, false, false, false, false, onGround,
                0.25, 0.0, true, true, true, launchHoldMs
        );
    }

    private static AvatarFlightController.Output output(boolean launchApplied) {
        return new AvatarFlightController.Output(
                launchApplied ? AvatarFlightMode.FORWARD_FLIGHT : AvatarFlightMode.GROUNDED,
                0.0, 0.0, 0.0, 0L, 0L, 0L, 0.0, 0.0, launchApplied,
                false, false, launchApplied, launchApplied ? 1.0 : 0.0,
                false, false, 0.0, 0.0, 0.0
        );
    }

    private static TransformComponent transform(double x, double y, double z) {
        return new TransformComponent(new Vector3d(x, y, z), new Rotation3f());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Emission(String systemId, double x, double y, double z, float yaw, float scale,
                            Ref<EntityStore> ownerRef) {
    }

    private static final class RecordingSink implements AvatarFlightLaunchVfxService.EmissionSink {
        private final List<Emission> emissions = new ArrayList<>();

        @Override
        public boolean emit(String systemId, double x, double y, double z, float yaw, float scale,
                            float maxDurationSeconds,
                            Ref<EntityStore> ownerRef,
                            com.hypixel.hytale.component.ComponentAccessor<
                                    com.hypixel.hytale.server.core.universe.world.storage.EntityStore> accessor) {
            emissions.add(new Emission(systemId, x, y, z, yaw, scale, ownerRef));
            return true;
        }
    }
}
