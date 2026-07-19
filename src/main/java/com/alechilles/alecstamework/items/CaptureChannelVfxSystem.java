package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Launches independently bounded beam particles for active capture channels. */
public final class CaptureChannelVfxSystem extends TickingSystem<EntityStore> {
    private static final long EMIT_INTERVAL_MS = 50L;
    private static final long TARGET_LOCK_GRACE_MS = 2_000L;
    private static final double DEFAULT_NATIVE_DURATION_SECONDS = 0.5D;
    private static final double DEFAULT_NATIVE_BEAM_LENGTH = 50.0D;
    private static final double MIN_DISTANCE = 0.01D;
    private static final double HELD_ITEM_RIGHT_OFFSET = 0.32D;
    private static final double HELD_ITEM_DOWN_OFFSET = 0.42D;
    private static final double HELD_ITEM_FORWARD_OFFSET = 0.28D;
    private static final Map<UUID, Session> ACTIVE = new ConcurrentHashMap<>();

    public static boolean start(@Nonnull UUID playerUuid,
                                @Nonnull UUID targetUuid,
                                @Nonnull World world,
                                @Nullable String particleSystem,
                                double nativeBeamLength,
                                double nativeDurationSeconds,
                                boolean scaleBeamToTarget,
                                double channelDurationSeconds,
                                double maxDistance,
                                @Nullable String auraEffectId) {
        if (channelDurationSeconds <= 0.0D) {
            return false;
        }
        double safeNativeLength = nativeBeamLength > 0.0D
                ? nativeBeamLength
                : DEFAULT_NATIVE_BEAM_LENGTH;
        double safeNativeDuration = nativeDurationSeconds > 0.0D
                ? nativeDurationSeconds
                : DEFAULT_NATIVE_DURATION_SECONDS;
        long nowMs = System.currentTimeMillis();
        long durationMs = Math.max(1L, Math.round(channelDurationSeconds * 1000.0D));
        long visualEndsAtMs = nowMs + durationMs;
        ACTIVE.put(playerUuid, new Session(
                playerUuid,
                targetUuid,
                world.getName(),
                particleSystem,
                safeNativeLength,
                safeNativeDuration,
                scaleBeamToTarget,
                maxDistance,
                auraEffectId,
                visualEndsAtMs,
                targetLockExpiresAt(visualEndsAtMs),
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
            if (playerRef == null || !playerRef.isValid()
                    || targetRef == null || !targetRef.isValid()) {
                expire(session, targetRef, store);
                continue;
            }
            if (nowMs >= session.expiresAtMs) {
                expire(session, targetRef, store);
                continue;
            }
            if (nowMs >= session.visualEndsAtMs) {
                endVisuals(session, targetRef, store);
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
        Vector3d sourceRoot = resolveRootPosition(playerRef, store);
        Vector3d targetRoot = resolveRootPosition(targetRef, store);
        if (!isWithinConfiguredRange(sourceRoot, targetRoot, session.maxDistance)) {
            return;
        }
        Vector3d source = resolveHeldItemPosition(playerRef, store);
        Vector3d target = resolveBeamTargetPosition(targetRef, store);
        if (source == null || target == null) {
            return;
        }
        Vector3d delta = new Vector3d(target).sub(source);
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance <= MIN_DISTANCE) {
            return;
        }
        Vector3d origin = new Vector3d(source);
        double visibleDistance = origin.distance(target);
        Rotation3f rotation = rotationForBeamPacket(new Vector3d(target).sub(origin));
        float scale = particleScaleForDistance(
                visibleDistance,
                session.nativeBeamLength,
                session.scaleBeamToTarget
        );
        float maxDuration = particleMaxDurationForDistance(
                visibleDistance,
                session.nativeBeamLength,
                session.nativeDurationSeconds,
                session.scaleBeamToTarget
        );
        if (scale <= 0.0F || maxDuration <= 0.0F) {
            return;
        }
        ParticleUtil.spawnParticleEffect(
                session.particleSystem,
                origin,
                rotation.yaw(),
                rotation.pitch(),
                rotation.roll(),
                scale,
                maxDuration,
                store
        );
    }

    static long emissionIntervalMsForTests() {
        return EMIT_INTERVAL_MS;
    }

    static float particleScaleForDistance(double distance,
                                          double nativeBeamLength,
                                          boolean scaleBeamToTarget) {
        float distanceScale = scaleForDistance(distance, nativeBeamLength);
        if (distanceScale <= 0.0F) {
            return 0.0F;
        }
        return scaleBeamToTarget ? distanceScale : 1.0F;
    }

    static float particleMaxDurationForDistance(double distance,
                                                double nativeBeamLength,
                                                double nativeDurationSeconds,
                                                boolean scaleBeamToTarget) {
        float distanceScale = scaleForDistance(distance, nativeBeamLength);
        if (distanceScale <= 0.0F
                || !Double.isFinite(nativeDurationSeconds) || nativeDurationSeconds <= 0.0D) {
            return 0.0F;
        }
        return scaleBeamToTarget
                ? (float) nativeDurationSeconds
                : (float) (nativeDurationSeconds * distanceScale);
    }

    static float scaleForDistance(double distance, double nativeBeamLength) {
        if (!Double.isFinite(distance) || distance <= 0.0D
                || !Double.isFinite(nativeBeamLength) || nativeBeamLength <= 0.0D) {
            return 0.0F;
        }
        return (float) (distance / nativeBeamLength);
    }

    static boolean isWithinConfiguredRange(@Nullable Vector3d sourceRoot,
                                           @Nullable Vector3d targetRoot,
                                           double maxDistance) {
        if (sourceRoot == null || targetRoot == null) {
            return false;
        }
        double distance = sourceRoot.distance(targetRoot);
        return Double.isFinite(distance) && (maxDistance <= 0.0D || distance <= maxDistance);
    }

    static Rotation3f rotationForBeamPacket(@Nonnull Vector3d direction) {
        Rotation3f look = Rotation3f.lookAt(direction);
        // SpawnParticleSystem.Direction uses the inverse particle-space axis at render time.
        // Align packet-space -X with the target so Beam_Lightning2's rendered +X travels toward it.
        return look.mul(new Quaterniond().rotationY(-Math.PI / 2.0D));
    }

    @Nullable
    private static Vector3d resolveRootPosition(@Nonnull Ref<EntityStore> ref,
                                                @Nonnull Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        Vector3d position = transform.getPosition();
        return new Vector3d(position.x, position.y, position.z);
    }

    @Nullable
    private static Vector3d resolveHeldItemPosition(@Nonnull Ref<EntityStore> ref,
                                                    @Nonnull Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        var look = TargetUtil.getLook(ref, store);
        return new Vector3d(look.getPosition()).add(heldItemOffset(
                look.getRotation().yaw(),
                look.getRotation().pitch()
        ));
    }

    static Vector3d heldItemOffset(float yaw, float pitch) {
        Vector3d offset = new Vector3d();
        ProjectileComponent.computeStartOffset(
                true,
                HELD_ITEM_DOWN_OFFSET,
                HELD_ITEM_RIGHT_OFFSET,
                HELD_ITEM_FORWARD_OFFSET,
                yaw,
                pitch,
                offset
        );
        return offset;
    }

    @Nullable
    private static Vector3d resolveBeamTargetPosition(@Nonnull Ref<EntityStore> ref,
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
        return new Vector3d(position.x, position.y + targetAnchorHeight(eyeHeight), position.z);
    }

    static double targetAnchorHeight(double eyeHeight) {
        if (!Double.isFinite(eyeHeight) || eyeHeight <= 0.0D) {
            return 0.15D;
        }
        return Math.max(0.15D, Math.min(2.5D, eyeHeight * 0.45D));
    }

    static long targetLockExpiresAt(long visualEndsAtMs) {
        return visualEndsAtMs + TARGET_LOCK_GRACE_MS;
    }

    static boolean retainsTargetLock(long nowMs, long expiresAtMs) {
        return nowMs < expiresAtMs;
    }

    private static void endVisuals(@Nonnull Session session,
                                   @Nullable Ref<EntityStore> targetRef,
                                   @Nonnull Store<EntityStore> store) {
        if (session.visualsEnded) {
            return;
        }
        session.visualsEnded = true;
        if (targetRef != null && targetRef.isValid()
                && session.auraEffectId != null && !session.auraEffectId.isBlank()) {
            TameworkEntityEffectService.removeEffect(targetRef, session.auraEffectId, store);
        }
    }

    private static void expire(@Nonnull Session session,
                               @Nullable Ref<EntityStore> targetRef,
                               @Nonnull Store<EntityStore> store) {
        if (!ACTIVE.remove(session.playerUuid, session)) {
            return;
        }
        endVisuals(session, targetRef, store);
    }

    private static final class Session {
        private final UUID playerUuid;
        private final UUID targetUuid;
        private final String worldName;
        private final String particleSystem;
        private final double nativeBeamLength;
        private final double nativeDurationSeconds;
        private final boolean scaleBeamToTarget;
        private final double maxDistance;
        private final String auraEffectId;
        private final long visualEndsAtMs;
        private final long expiresAtMs;
        private volatile boolean visualsEnded;
        private volatile long nextEmitAtMs;

        private Session(UUID playerUuid,
                        UUID targetUuid,
                        String worldName,
                        String particleSystem,
                        double nativeBeamLength,
                        double nativeDurationSeconds,
                        boolean scaleBeamToTarget,
                        double maxDistance,
                        String auraEffectId,
                        long visualEndsAtMs,
                        long expiresAtMs,
                        long nextEmitAtMs) {
            this.playerUuid = playerUuid;
            this.targetUuid = targetUuid;
            this.worldName = worldName;
            this.particleSystem = particleSystem;
            this.nativeBeamLength = nativeBeamLength;
            this.nativeDurationSeconds = nativeDurationSeconds;
            this.scaleBeamToTarget = scaleBeamToTarget;
            this.maxDistance = maxDistance;
            this.auraEffectId = auraEffectId;
            this.visualEndsAtMs = visualEndsAtMs;
            this.expiresAtMs = expiresAtMs;
            this.nextEmitAtMs = nextEmitAtMs;
        }
    }
}
