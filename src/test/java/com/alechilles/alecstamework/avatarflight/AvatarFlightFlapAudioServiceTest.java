package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarFlightFlapAudioServiceTest {
    private static final String FLAP_EVENT = "SFX_Test_Flap";

    @Test
    void forwardFlightEmitsOnceOnEntryAndThenAtConfiguredCadence() throws Exception {
        RecordingSink sink = new RecordingSink();
        AvatarFlightFlapAudioService service = new AvatarFlightFlapAudioService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        TwAvatarFlightConfig config = configuredAudio();

        service.tick(flight, output(false, false), config, transform(), 1_000L, null);
        service.tick(flight, output(false, false), config, transform(), 2_332L, null);
        service.tick(flight, output(false, false), config, transform(), 2_333L, null);

        assertEquals(List.of(FLAP_EVENT, FLAP_EVENT), sink.events);
        assertEquals("Flight", flight.getFlightFlapAudioMode());
        assertEquals(3_666L, flight.getNextFlightFlapAudioAtMs());
    }

    @Test
    void delayedTickEmitsOnlyOnceAndReschedulesFromCurrentTime() throws Exception {
        RecordingSink sink = new RecordingSink();
        AvatarFlightFlapAudioService service = new AvatarFlightFlapAudioService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        TwAvatarFlightConfig config = configuredAudio();

        service.tick(flight, output(false, false), config, transform(), 1_000L, null);
        service.tick(flight, output(false, false), config, transform(), 10_000L, null);

        assertEquals(2, sink.events.size());
        assertEquals(11_333L, flight.getNextFlightFlapAudioAtMs());
    }

    @Test
    void enteringHoverImmediatelyStartsItsIndependentCadence() throws Exception {
        RecordingSink sink = new RecordingSink();
        AvatarFlightFlapAudioService service = new AvatarFlightFlapAudioService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        TwAvatarFlightConfig config = configuredAudio();

        service.tick(flight, output(false, false), config, transform(), 1_000L, null);
        service.tick(flight, output(true, false), config, transform(), 1_100L, null);
        service.tick(flight, output(true, false), config, transform(), 1_988L, null);
        service.tick(flight, output(true, false), config, transform(), 1_989L, null);

        assertEquals(3, sink.events.size());
        assertEquals("Idle", flight.getFlightFlapAudioMode());
        assertEquals(2_878L, flight.getNextFlightFlapAudioAtMs());
    }

    @Test
    void fastFlightAndGroundedStatesResetCadence() throws Exception {
        RecordingSink sink = new RecordingSink();
        AvatarFlightFlapAudioService service = new AvatarFlightFlapAudioService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        TwAvatarFlightConfig config = configuredAudio();

        service.tick(flight, output(false, false), config, transform(), 1_000L, null);
        service.tick(flight, output(false, true), config, transform(), 1_100L, null);
        service.tick(flight, output(false, false), config, transform(), 1_200L, null);
        service.tick(flight, output(false, false, false), config, transform(), 1_300L, null);

        assertEquals(2, sink.events.size());
        assertEquals("", flight.getFlightFlapAudioMode());
        assertEquals(0L, flight.getNextFlightFlapAudioAtMs());
    }

    @Test
    void defaultConfigDoesNotAddFlapsToExistingSpecies() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightFlapAudioService service = new AvatarFlightFlapAudioService(sink);

        service.tick(new AvatarFlightComponent(), output(false, false),
                TwAvatarFlightConfig.defaultConfig(), transform(), 1_000L, null);

        assertEquals(0, sink.events.size());
    }

    private static TwAvatarFlightConfig configuredAudio() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setField(config.getAudio(), "idleFlightFlapSoundEvent", FLAP_EVENT);
        setField(config.getAudio(), "idleFlightFlapIntervalMs", 889.0);
        setField(config.getAudio(), "flightFlapSoundEvent", FLAP_EVENT);
        setField(config.getAudio(), "flightFlapIntervalMs", 1_333.0);
        return config;
    }

    private static AvatarFlightController.Output output(boolean idle, boolean fast) {
        return output(idle, fast, true);
    }

    private static AvatarFlightController.Output output(boolean idle, boolean fast, boolean applyingVelocity) {
        return new AvatarFlightController.Output(
                AvatarFlightMode.FORWARD_FLIGHT,
                0.0, 0.0, 0.0,
                0L, 0L, 0L,
                0.0, 0.0,
                applyingVelocity, false, false, false, 0.0,
                idle, fast, 0.0, 0.0, 0.0, false
        );
    }

    private static TransformComponent transform() {
        return new TransformComponent(new Vector3d(1.0, 2.0, 3.0), new Rotation3f());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RecordingSink implements AvatarFlightFlapAudioService.PlaybackSink {
        private final List<String> events = new ArrayList<>();

        @Override
        public boolean play(String soundEventId, double x, double y, double z, float volume, float pitch,
                            com.hypixel.hytale.component.ComponentAccessor<
                                    com.hypixel.hytale.server.core.universe.world.storage.EntityStore> accessor) {
            events.add(soundEventId);
            return true;
        }
    }
}
