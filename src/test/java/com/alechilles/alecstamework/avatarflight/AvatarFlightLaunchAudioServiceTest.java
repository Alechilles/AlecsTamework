package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightAudioSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightLaunchAudioServiceTest {
    private static final double EPSILON = 0.00001;

    @Test
    void groundedChargeEmitsDynamicPulseAndSchedulesCadence() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchAudioService service = new AvatarFlightLaunchAudioService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        AvatarFlightInputComponent input = chargingInput(1000L, true);

        service.tick(flight, input, input(0L, true), output(false), TwAvatarFlightConfig.defaultConfig(),
                transform(1.0, 2.0, 3.0), 1000L, null);
        service.tick(flight, input, input(0L, true), output(false), TwAvatarFlightConfig.defaultConfig(),
                transform(1.0, 2.0, 3.0), 1500L, null);

        assertEquals(1, sink.playbacks.size());
        Playback playback = sink.playbacks.getFirst();
        assertEquals(AvatarFlightAudioSettings.DEFAULT_CHARGE_SOUND, playback.soundEventId());
        assertEquals(0.32f, playback.volume(), EPSILON);
        assertEquals(0.85f, playback.pitch(), EPSILON);
        assertEquals(1600L, flight.getNextLaunchChargeAudioAtMs());
    }

    @Test
    void initialPulseEmitsWithNegativeWorldTimestamp() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchAudioService service = new AvatarFlightLaunchAudioService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        AvatarFlightInputComponent input = chargingInput(-2000L, true);

        service.tick(flight, input, input(0L, true), output(false), TwAvatarFlightConfig.defaultConfig(),
                transform(1.0, 2.0, 3.0), -2000L, null);

        assertEquals(1, sink.count(AvatarFlightAudioSettings.DEFAULT_CHARGE_SOUND));
        assertEquals(-1400L, flight.getNextLaunchChargeAudioAtMs());
    }

    @Test
    void fullChargeCuePlaysOnceWhilePulsesContinue() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchAudioService service = new AvatarFlightLaunchAudioService(sink);
        AvatarFlightComponent flight = new AvatarFlightComponent();
        AvatarFlightInputComponent input = chargingInput(1000L, true);
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

        service.tick(flight, input, input(0L, true), output(false), config,
                transform(1.0, 2.0, 3.0), 4000L, null);
        service.tick(flight, input, input(0L, true), output(false), config,
                transform(1.0, 2.0, 3.0), 4180L, null);

        assertEquals(1, sink.count(AvatarFlightAudioSettings.DEFAULT_READY_SOUND));
        assertEquals(2, sink.count(AvatarFlightAudioSettings.DEFAULT_CHARGE_SOUND));
        assertTrue(flight.isLaunchFullChargeAudioPlayed());
    }

    @Test
    void rejectedReleasePlaysCancelAndClearsAudioState() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchAudioService service = new AvatarFlightLaunchAudioService(sink);
        AvatarFlightComponent flight = primedFlight();

        service.tick(flight, new AvatarFlightInputComponent(), input(200L, true), output(false),
                TwAvatarFlightConfig.defaultConfig(), transform(7.0, 8.0, 9.0), 2000L, null);

        assertEquals(1, sink.count(AvatarFlightAudioSettings.DEFAULT_CANCEL_SOUND));
        assertEquals(0L, flight.getNextLaunchChargeAudioAtMs());
        assertFalse(flight.isLaunchFullChargeAudioPlayed());
    }

    @Test
    void successfulReleaseSelectsConfiguredStrengthTier() throws Exception {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchAudioService service = new AvatarFlightLaunchAudioService(sink);
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setField(config.getLaunch(), "minChargeMs", 0.0);
        setField(config.getLaunch(), "maxChargeMs", 1000.0);
        setField(config.getLaunch(), "chargeExponent", 1.0);

        service.tick(primedFlight(), new AvatarFlightInputComponent(), input(800L, true), output(true),
                config, transform(4.0, 5.0, 6.0), 2000L, null);

        assertEquals(1, sink.playbacks.size());
        assertEquals(AvatarFlightAudioSettings.DEFAULT_FULL_SOUND, sink.playbacks.getFirst().soundEventId());
    }

    @Test
    void disabledOrBlankAudioDoesNotEmit() throws Exception {
        RecordingSink sink = new RecordingSink();
        AvatarFlightLaunchAudioService service = new AvatarFlightLaunchAudioService(sink);
        TwAvatarFlightConfig disabled = TwAvatarFlightConfig.defaultConfig();
        setField(disabled.getAudio(), "enabled", false);

        service.tick(primedFlight(), chargingInput(1000L, true), input(0L, true), output(false),
                disabled, transform(1.0, 2.0, 3.0), 1000L, null);

        TwAvatarFlightConfig blank = TwAvatarFlightConfig.defaultConfig();
        setField(blank.getAudio(), "launchChargeSoundEvent", "");
        AvatarFlightComponent flight = new AvatarFlightComponent();
        service.tick(flight, chargingInput(1000L, true), input(0L, true), output(false),
                blank, transform(1.0, 2.0, 3.0), 1000L, null);

        assertTrue(sink.playbacks.isEmpty());
        assertEquals(1600L, flight.getNextLaunchChargeAudioAtMs());
    }

    private static AvatarFlightComponent primedFlight() {
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.setNextLaunchChargeAudioAtMs(1234L);
        flight.setLaunchFullChargeAudioPlayed(true);
        return flight;
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

    private record Playback(String soundEventId, double x, double y, double z, float volume, float pitch) {
    }

    private static final class RecordingSink implements AvatarFlightLaunchAudioService.PlaybackSink {
        private final List<Playback> playbacks = new ArrayList<>();

        @Override
        public boolean play(String soundEventId, double x, double y, double z, float volume, float pitch,
                            com.hypixel.hytale.component.ComponentAccessor<
                                    com.hypixel.hytale.server.core.universe.world.storage.EntityStore> accessor) {
            playbacks.add(new Playback(soundEventId, x, y, z, volume, pitch));
            return true;
        }

        private long count(String soundEventId) {
            return playbacks.stream().filter(playback -> soundEventId.equals(playback.soundEventId())).count();
        }
    }
}
