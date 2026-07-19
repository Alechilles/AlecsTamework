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
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightAbilityAudioServiceTest {
    @Test
    void successfulManeuversEmitEachConfiguredCueAtAvatarPosition() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightAbilityAudioService service = new AvatarFlightAbilityAudioService(sink);

        service.emitApplied(output(true, true, true), TwAvatarFlightConfig.defaultConfig(),
                transform(1.0, 2.0, 3.0), null);

        assertEquals(3, sink.playbacks.size());
        assertEquals(AvatarFlightAudioSettings.DEFAULT_UPWARD_FLAP_SOUND,
                sink.playbacks.get(0).soundEventId());
        assertEquals(AvatarFlightAudioSettings.DEFAULT_FORWARD_BOOST_SOUND,
                sink.playbacks.get(1).soundEventId());
        assertEquals(AvatarFlightAudioSettings.DEFAULT_AIRBRAKE_SOUND,
                sink.playbacks.get(2).soundEventId());
        assertEquals(1.0, sink.playbacks.getFirst().x());
        assertEquals(2.0, sink.playbacks.getFirst().y());
        assertEquals(3.0, sink.playbacks.getFirst().z());
    }

    @Test
    void rejectedManeuversAndMissingPositionRemainSilent() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightAbilityAudioService service = new AvatarFlightAbilityAudioService(sink);
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

        service.emitApplied(output(false, false, false), config, transform(1.0, 2.0, 3.0), null);
        service.emitApplied(output(true, true, true), config, null, null);

        assertTrue(sink.playbacks.isEmpty());
    }

    @Test
    void disabledAndExplicitlyBlankCuesRemainSilent() throws Exception {
        RecordingSink sink = new RecordingSink();
        AvatarFlightAbilityAudioService service = new AvatarFlightAbilityAudioService(sink);
        TwAvatarFlightConfig disabled = TwAvatarFlightConfig.defaultConfig();
        setField(disabled.getAudio(), "enabled", false);
        service.emitApplied(output(true, true, true), disabled, transform(1.0, 2.0, 3.0), null);

        TwAvatarFlightConfig blank = TwAvatarFlightConfig.defaultConfig();
        setField(blank.getAudio(), "upwardFlapSoundEvent", "");
        setField(blank.getAudio(), "forwardBoostSoundEvent", "");
        setField(blank.getAudio(), "airbrakeSoundEvent", "");
        service.emitApplied(output(true, true, true), blank, transform(1.0, 2.0, 3.0), null);

        assertTrue(sink.playbacks.isEmpty());
    }

    private static AvatarFlightController.Output output(boolean jumpApplied,
                                                         boolean boostApplied,
                                                         boolean airbrakeApplied) {
        return new AvatarFlightController.Output(
                AvatarFlightMode.FORWARD_FLIGHT,
                0.0, 0.0, 0.0,
                0L, 0L, 0L,
                0.0, 0.0,
                true, jumpApplied, boostApplied, false, 0.0,
                false, false, 0.0, 0.0, 0.0, airbrakeApplied
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

    private record Playback(String soundEventId, double x, double y, double z) {
    }

    private static final class RecordingSink implements AvatarFlightAbilityAudioService.PlaybackSink {
        private final List<Playback> playbacks = new ArrayList<>();

        @Override
        public boolean play(String soundEventId, double x, double y, double z, float volume, float pitch,
                            com.hypixel.hytale.component.ComponentAccessor<
                                    com.hypixel.hytale.server.core.universe.world.storage.EntityStore> accessor) {
            if (soundEventId.isBlank()) return false;
            playbacks.add(new Playback(soundEventId, x, y, z));
            return true;
        }
    }
}
