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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightBoostVfxServiceTest {
    private static final double EPSILON = 0.00001;

    @Test
    void simultaneousSuccessfulBoostsEmitBothConfiguredSystems() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightBoostVfxService service = new AvatarFlightBoostVfxService(sink);
        Ref<EntityStore> ownerRef = new Ref<>(null, 7);

        service.emitApplied(output(true, true), TwAvatarFlightConfig.defaultConfig(),
                transform(1.0, 2.0, 3.0), 0.75, ownerRef, null);

        assertEquals(2, sink.emissions.size());
        Emission upward = sink.emissions.get(0);
        assertEquals(AvatarFlightVfxSettings.DEFAULT_UPWARD_BOOST_SYSTEM, upward.systemId());
        assertEquals(1.0f, upward.scale(), EPSILON);
        assertEquals(1.0, upward.x(), EPSILON);
        assertEquals(2.0, upward.y(), EPSILON);
        assertEquals(3.0, upward.z(), EPSILON);
        assertEquals(0.75f, upward.yaw(), EPSILON);
        assertSame(ownerRef, upward.ownerRef());

        Emission forward = sink.emissions.get(1);
        assertEquals(AvatarFlightVfxSettings.DEFAULT_FORWARD_BOOST_SYSTEM, forward.systemId());
        assertEquals(1.0f, forward.scale(), EPSILON);
    }

    @Test
    void effectSpecificSettingsControlSystemsScalesAndDisabling() throws Exception {
        RecordingSink sink = new RecordingSink();
        AvatarFlightBoostVfxService service = new AvatarFlightBoostVfxService(sink);
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setField(config.getVfx(), "upwardBoostEnabled", false);
        setField(config.getVfx(), "forwardBoostParticleSystem", "CustomForwardBoost");
        setField(config.getVfx(), "forwardBoostScale", 4.5);

        service.emitApplied(output(true, true), config, transform(4.0, 5.0, 6.0), 0.25, null, null);

        assertEquals(1, sink.emissions.size());
        assertEquals("CustomForwardBoost", sink.emissions.getFirst().systemId());
        assertEquals(4.5f, sink.emissions.getFirst().scale(), EPSILON);
    }

    @Test
    void unsuccessfulOrUnpositionedBoostsDoNotEmit() {
        RecordingSink sink = new RecordingSink();
        AvatarFlightBoostVfxService service = new AvatarFlightBoostVfxService(sink);
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

        service.emitApplied(output(false, false), config, transform(1.0, 2.0, 3.0), 0.0, null, null);
        service.emitApplied(output(true, true), config, null, 0.0, null, null);

        assertTrue(sink.emissions.isEmpty());
    }

    private static AvatarFlightController.Output output(boolean jumpApplied, boolean boostApplied) {
        return new AvatarFlightController.Output(
                AvatarFlightMode.FORWARD_FLIGHT,
                0.0, 0.0, 0.0,
                0L, 0L, 0L,
                0.0, 0.0,
                true, jumpApplied, boostApplied, false, 0.0,
                false, boostApplied, 0.0, 0.0, 0.0
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

    private static final class RecordingSink implements AvatarFlightBoostVfxService.EmissionSink {
        private final List<Emission> emissions = new ArrayList<>();

        @Override
        public boolean emit(String systemId, double x, double y, double z, float yaw, float scale,
                            float maxDurationSeconds,
                            Ref<EntityStore> ownerRef,
                            com.hypixel.hytale.component.ComponentAccessor<EntityStore> accessor) {
            emissions.add(new Emission(systemId, x, y, z, yaw, scale, ownerRef));
            return true;
        }
    }
}
