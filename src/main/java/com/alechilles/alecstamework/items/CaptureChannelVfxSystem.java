package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Emits short, bounded beam segments for active capture channels. */
public final class CaptureChannelVfxSystem extends TickingSystem<EntityStore> {
    private static final long EMIT_INTERVAL_MS = 100L;
    private static final float SEGMENT_MAX_DURATION_SECONDS = 0.18F;
    private static final double DEFAULT_NATIVE_BEAM_LENGTH = 50.0D;
    private static final double MIN_DISTANCE = 0.01D;
    private static final Map<UUID, Session> ACTIVE = new ConcurrentHashMap<>();

    public static boolean start(@Nonnull UUID playerUuid,
                                @Nonnull UUID targetUuid,
                                @Nonnull World world,
                                @Nullable String particleSystem,
                                double nativeBeamLength,
                                double channelDurationSeconds,
                                double maxDistance,
                                @Nullable String auraEffectId) {
        if (channelDurationSeconds <= 0.0D) {
            return false;
        }
        double safeNativeLength = nativeBeamLength > 0.0D
                ? nativeBeamLength
                : DEFAULT_NATIVE_BEAM_LENGTH;
        long nowMs = System.currentTimeMillis();
        long durationMs = Math.max(1L, Math.round(channelDurationSeconds * 1000.0D));
        ACTIVE.put(playerUuid, new Session(
                playerUuid,
                targetUuid,
                world.getName(),
                particleSystem,
                safeNativeLength,
                maxDistance,
                auraEffectId,
                nowMs + durationMs + 250L,
                nowMs
        ));
        return true;
    }

    @Nullable
    public static Ref<EntityStore> resolveTarget(@Nonnull UUID playerUuid, @Nonnull World world) {
        Session session = ACTIVE.get(playerUuid);
        if (session == null || !session.worldName.equals(world.getName())) {
            return null;
        }
        Ref<EntityStore> targetRef = world.getEntityRef(session.targetUuid);
        return targetRef != null && targetRef.isValid() ? targetRef : null;
    }

    @Nullable
    public static Ref<EntityStore> stop(@Nonnull UUID playerUuid, @Nullable World world) {
        Session session = ACTIVE.remove(playerUuid);
        if (session == null || world == null || !session.worldName.equals(world.getName())) {
            return null;
        }
        Ref<EntityStore> targetRef = world.getEntityRef(session.targetUuid);
        return targetRef != null && targetRef.isValid() ? targetRef : null;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null || ACTIVE.isEmpty()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        for (Session session : ACTIVE.values()) {
            if (!session.worldName.equals(world.getName())) {
                continue;
            }
            Ref<EntityStore> playerRef = world.getEntityRef(session.playerUuid);
            Ref<EntityStore> targetRef = world.getEntityRef(session.targetUuid);
            if (nowMs >= session.expiresAtMs
                    || playerRef == null || !playerRef.isValid()
                    || targetRef == null || !targetRef.isValid()) {
                expire(session, targetRef, store);
                continue;
            }
            if (nowMs < session.nextEmitAtMs) {
                continue;
            }
            session.nextEmitAtMs = nowMs + EMIT_INTERVAL_MS;
            emit(session, playerRef, targetRef, store);
        }
    }

    private static void emit(@Nonnull Session session,
                             @Nonnull Ref<EntityStore> playerRef,
                             @Nonnull Ref<EntityStore> targetRef,
                             @Nonnull Store<EntityStore> store) {
        if (session.particleSystem == null || session.particleSystem.isBlank()) {
            return;
        }
        Vector3d source = resolveEyePosition(playerRef, store);
        Vector3d target = resolveEyePosition(targetRef, store);
        if (source == null || target == null) {
            return;
        }
        Vector3d delta = new Vector3d(target).sub(source);
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance <= MIN_DISTANCE
                || session.maxDistance > 0.0D && distance > session.maxDistance) {
            return;
        }
        // Pull the source slightly away from the camera while preserving the exact endpoint scale.
        Vector3d origin = new Vector3d(source).fma(0.04D, delta);
        double visibleDistance = origin.distance(target);
        Rotation3f rotation = rotationForPositiveXBeam(new Vector3d(target).sub(origin));
        float scale = scaleForDistance(visibleDistance, session.nativeBeamLength);
        ParticleUtil.spawnParticleEffect(
                session.particleSystem,
                origin,
                rotation.yaw(),
                rotation.pitch(),
                rotation.roll(),
                scale,
                SEGMENT_MAX_DURATION_SECONDS,
                store
        );
    }

    static float scaleForDistance(double distance, double nativeBeamLength) {
        if (!Double.isFinite(distance) || distance <= 0.0D
                || !Double.isFinite(nativeBeamLength) || nativeBeamLength <= 0.0D) {
            return 0.0F;
        }
        return (float) (distance / nativeBeamLength);
    }

    static Rotation3f rotationForPositiveXBeam(@Nonnull Vector3d direction) {
        Rotation3f look = Rotation3f.lookAt(direction);
        // Hytale look rotations aim local -Z; Beam_Lightning2 is authored along local +X.
        return look.mul(new Quaterniond().rotationY(Math.PI / 2.0D));
    }

    @Nullable
    private static Vector3d resolveEyePosition(@Nonnull Ref<EntityStore> ref,
                                               @Nonnull Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        double eyeHeight = 0.0D;
        ModelComponent model = store.getComponent(ref, ModelComponent.getComponentType());
        if (model != null && model.getModel() != null) {
            eyeHeight = model.getModel().getEyeHeight(ref, store);
        }
        Vector3d position = transform.getPosition();
        return new Vector3d(position.x, position.y + eyeHeight, position.z);
    }

    private static void expire(@Nonnull Session session,
                               @Nullable Ref<EntityStore> targetRef,
                               @Nonnull Store<EntityStore> store) {
        if (!ACTIVE.remove(session.playerUuid, session)) {
            return;
        }
        if (targetRef != null && targetRef.isValid()
                && session.auraEffectId != null && !session.auraEffectId.isBlank()) {
            TameworkEntityEffectService.removeEffect(targetRef, session.auraEffectId, store);
        }
    }

    private static final class Session {
        private final UUID playerUuid;
        private final UUID targetUuid;
        private final String worldName;
        private final String particleSystem;
        private final double nativeBeamLength;
        private final double maxDistance;
        private final String auraEffectId;
        private final long expiresAtMs;
        private volatile long nextEmitAtMs;

        private Session(UUID playerUuid,
                        UUID targetUuid,
                        String worldName,
                        String particleSystem,
                        double nativeBeamLength,
                        double maxDistance,
                        String auraEffectId,
                        long expiresAtMs,
                        long nextEmitAtMs) {
            this.playerUuid = playerUuid;
            this.targetUuid = targetUuid;
            this.worldName = worldName;
            this.particleSystem = particleSystem;
            this.nativeBeamLength = nativeBeamLength;
            this.maxDistance = maxDistance;
            this.auraEffectId = auraEffectId;
            this.expiresAtMs = expiresAtMs;
            this.nextEmitAtMs = nextEmitAtMs;
        }
    }
}
