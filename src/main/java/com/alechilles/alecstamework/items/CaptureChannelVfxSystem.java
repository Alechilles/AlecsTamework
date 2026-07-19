package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileSessionRegistry;
import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileSpawner;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Owns capture-channel VFX sessions, including independently homing model-particle motes. */
public final class CaptureChannelVfxSystem extends TickingSystem<EntityStore> {
    private static final long LEGACY_EMIT_INTERVAL_MS = 50L;
    private static final long TARGET_LOCK_GRACE_MS = 2_000L;
    private static final double DEFAULT_NATIVE_DURATION_SECONDS = 0.5D;
    private static final double DEFAULT_NATIVE_BEAM_LENGTH = 50.0D;
    private static final double MIN_DISTANCE = 0.01D;
    private static final Map<UUID, Session> ACTIVE = new ConcurrentHashMap<>();
    private static final Set<String> WARNED_HOMING_MODELS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();

    public static boolean start(@Nonnull UUID playerUuid,
                                @Nonnull UUID targetUuid,
                                @Nonnull World world,
                                @Nullable String particleSystem,
                                double nativeBeamLength,
                                double nativeDurationSeconds,
                                boolean scaleBeamToTarget,
                                boolean beamFromTarget,
                                double channelDurationSeconds,
                                double maxDistance,
                                @Nullable String auraEffectId) {
        return start(
                playerUuid,
                targetUuid,
                world,
                particleSystem,
                nativeBeamLength,
                nativeDurationSeconds,
                scaleBeamToTarget,
                beamFromTarget,
                channelDurationSeconds,
                maxDistance,
                auraEffectId,
                CaptureHomingProjectileSettings.disabled()
        );
    }

    public static boolean start(@Nonnull UUID playerUuid,
                                @Nonnull UUID targetUuid,
                                @Nonnull World world,
                                @Nullable String particleSystem,
                                double nativeBeamLength,
                                double nativeDurationSeconds,
                                boolean scaleBeamToTarget,
                                boolean beamFromTarget,
                                double channelDurationSeconds,
                                double maxDistance,
                                @Nullable String auraEffectId,
                                @Nullable CaptureHomingProjectileSettings homingSettings) {
        if (!Double.isFinite(channelDurationSeconds) || channelDurationSeconds <= 0.0D) {
            return false;
        }
        double safeNativeLength = nativeBeamLength > 0.0D
                ? nativeBeamLength
                : DEFAULT_NATIVE_BEAM_LENGTH;
        double safeNativeDuration = nativeDurationSeconds > 0.0D
                ? nativeDurationSeconds
                : DEFAULT_NATIVE_DURATION_SECONDS;
        CaptureHomingProjectileSettings safeHoming = homingSettings == null
                ? CaptureHomingProjectileSettings.disabled()
                : homingSettings;
        long nowMs = System.currentTimeMillis();
        long durationMs = Math.max(1L, Math.round(channelDurationSeconds * 1000.0D));
        long visualEndsAtMs = nowMs + durationMs;
        long generation = nextGeneration();
        Session session = new Session(
                playerUuid,
                targetUuid,
                world.getName(),
                generation,
                particleSystem,
                safeNativeLength,
                safeNativeDuration,
                scaleBeamToTarget,
                beamFromTarget,
                maxDistance,
                auraEffectId,
                safeHoming,
                visualEndsAtMs,
                targetLockExpiresAt(visualEndsAtMs),
                nowMs
        );
        Session previous = ACTIVE.put(playerUuid, session);
        if (previous != null) {
            deactivate(previous);
            if (previous.worldName.equals(world.getName()) && world.getEntityStore() != null) {
                endVisuals(previous, world.getEntityRef(previous.targetUuid), world.getEntityStore().getStore());
            }
        }
        if (safeHoming.isEnabled()) {
            HomingVisualProjectileSessionRegistry.activate(
                    session.worldName,
                    session.playerUuid.toString(),
                    session.generation
            );
        }
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
        if (session == null) {
            return null;
        }
        deactivate(session);
        if (world == null || !session.worldName.equals(world.getName())) {
            return null;
        }
        Ref<EntityStore> targetRef = world.getEntityRef(session.targetUuid);
        if (world.getEntityStore() != null) {
            endVisuals(session, targetRef, world.getEntityStore().getStore());
        }
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
            session.nextEmitAtMs = nowMs + session.emissionIntervalMs();
            emit(session, playerRef, targetRef, store);
        }
    }

    private static void emit(@Nonnull Session session,
                             @Nonnull Ref<EntityStore> playerRef,
                             @Nonnull Ref<EntityStore> targetRef,
                             @Nonnull Store<EntityStore> store) {
        Vector3d playerRoot = CaptureChannelAnchorResolver.resolveRoot(playerRef, store);
        Vector3d targetRoot = CaptureChannelAnchorResolver.resolveRoot(targetRef, store);
        if (!isWithinConfiguredRange(playerRoot, targetRoot, session.maxDistance)) {
            return;
        }

        if (session.useHoming()) {
            if (HomingVisualProjectileSpawner.countForSession(
                    store, session.playerUuid, session.generation) >= session.homingSettings.getMaxConcurrent()
                    || HomingVisualProjectileSpawner.countInWorld(store)
                    >= HomingVisualProjectileSpawner.DEFAULT_WORLD_CAP) {
                return;
            }
            Vector3d origin = CaptureChannelAnchorResolver.resolveBody(targetRef, store);
            if (origin == null) {
                return;
            }
            HomingVisualProjectileSpawner.SpawnResult result = HomingVisualProjectileSpawner.spawn(
                    store,
                    origin,
                    session.playerUuid,
                    session.homingSettings.toProjectileSpec(),
                    session.playerUuid,
                    session.targetUuid,
                    session.generation
            );
            if (result == HomingVisualProjectileSpawner.SpawnResult.SPAWNED
                    || result == HomingVisualProjectileSpawner.SpawnResult.CAPPED) {
                return;
            }
            session.homingFallback = true;
            deactivate(session);
            warnHomingFallback(session.homingSettings.getModelId(), result);
        }

        emitLegacyBeam(session, playerRef, targetRef, store);
    }

    private static void emitLegacyBeam(@Nonnull Session session,
                                       @Nonnull Ref<EntityStore> playerRef,
                                       @Nonnull Ref<EntityStore> targetRef,
                                       @Nonnull Store<EntityStore> store) {
        if (session.particleSystem == null || session.particleSystem.isBlank()) {
            return;
        }
        Vector3d heldItemPosition = CaptureChannelAnchorResolver.resolveHeldItem(playerRef, store);
        Vector3d targetPosition = CaptureChannelAnchorResolver.resolveBody(targetRef, store);
        if (heldItemPosition == null || targetPosition == null) {
            return;
        }
        Vector3d source = beamOrigin(heldItemPosition, targetPosition, session.beamFromTarget);
        Vector3d target = beamDestination(heldItemPosition, targetPosition, session.beamFromTarget);
        Vector3d delta = new Vector3d(target).sub(source);
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance <= MIN_DISTANCE) {
            return;
        }
        Rotation3f rotation = rotationForBeamPacket(delta);
        float scale = particleScaleForDistance(
                distance,
                session.nativeBeamLength,
                session.scaleBeamToTarget
        );
        float maxDuration = particleMaxDurationForDistance(
                distance,
                session.nativeBeamLength,
                session.nativeDurationSeconds,
                session.scaleBeamToTarget
        );
        if (scale <= 0.0F || maxDuration <= 0.0F) {
            return;
        }
        ParticleUtil.spawnParticleEffect(
                session.particleSystem,
                source,
                rotation.yaw(),
                rotation.pitch(),
                rotation.roll(),
                scale,
                maxDuration,
                store
        );
    }

    static long emissionIntervalMsForTests() {
        return LEGACY_EMIT_INTERVAL_MS;
    }

    static long homingEmissionIntervalMsForTests(@Nonnull CaptureHomingProjectileSettings settings) {
        return settings.getSpawnIntervalMs();
    }

    static Vector3d beamOrigin(@Nonnull Vector3d heldItemPosition,
                               @Nonnull Vector3d targetPosition,
                               boolean beamFromTarget) {
        return new Vector3d(beamFromTarget ? targetPosition : heldItemPosition);
    }

    static Vector3d beamDestination(@Nonnull Vector3d heldItemPosition,
                                    @Nonnull Vector3d targetPosition,
                                    boolean beamFromTarget) {
        return new Vector3d(beamFromTarget ? heldItemPosition : targetPosition);
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
        return CaptureChannelAnchorResolver.isWithinRange(sourceRoot, targetRoot, maxDistance);
    }

    static Rotation3f rotationForBeamPacket(@Nonnull Vector3d direction) {
        Rotation3f look = Rotation3f.lookAt(direction);
        return look.mul(new Quaterniond().rotationY(-Math.PI / 2.0D));
    }

    static Vector3d heldItemOffset(float yaw, float pitch) {
        return CaptureChannelAnchorResolver.heldItemOffset(yaw, pitch);
    }

    static double targetAnchorHeight(double eyeHeight) {
        return CaptureChannelAnchorResolver.bodyAnchorHeight(eyeHeight);
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
        deactivate(session);
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

    private static void deactivate(@Nonnull Session session) {
        HomingVisualProjectileSessionRegistry.deactivate(
                session.worldName,
                session.playerUuid.toString(),
                session.generation
        );
    }

    private static long nextGeneration() {
        long generation = NEXT_GENERATION.incrementAndGet();
        if (generation > 0L) {
            return generation;
        }
        NEXT_GENERATION.set(1L);
        return 1L;
    }

    private static void warnHomingFallback(@Nonnull String modelId,
                                           @Nonnull HomingVisualProjectileSpawner.SpawnResult result) {
        String key = modelId + ':' + result.name();
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && WARNED_HOMING_MODELS.add(key)) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Capture homing VFX could not launch model '%s' (%s); using BeamParticleSystem fallback.",
                    modelId,
                    result
            );
        }
    }

    private static final class Session {
        private final UUID playerUuid;
        private final UUID targetUuid;
        private final String worldName;
        private final long generation;
        private final String particleSystem;
        private final double nativeBeamLength;
        private final double nativeDurationSeconds;
        private final boolean scaleBeamToTarget;
        private final boolean beamFromTarget;
        private final double maxDistance;
        private final String auraEffectId;
        private final CaptureHomingProjectileSettings homingSettings;
        private final long visualEndsAtMs;
        private final long expiresAtMs;
        private volatile boolean visualsEnded;
        private volatile boolean homingFallback;
        private volatile long nextEmitAtMs;

        private Session(UUID playerUuid,
                        UUID targetUuid,
                        String worldName,
                        long generation,
                        String particleSystem,
                        double nativeBeamLength,
                        double nativeDurationSeconds,
                        boolean scaleBeamToTarget,
                        boolean beamFromTarget,
                        double maxDistance,
                        String auraEffectId,
                        CaptureHomingProjectileSettings homingSettings,
                        long visualEndsAtMs,
                        long expiresAtMs,
                        long nextEmitAtMs) {
            this.playerUuid = playerUuid;
            this.targetUuid = targetUuid;
            this.worldName = worldName;
            this.generation = generation;
            this.particleSystem = particleSystem;
            this.nativeBeamLength = nativeBeamLength;
            this.nativeDurationSeconds = nativeDurationSeconds;
            this.scaleBeamToTarget = scaleBeamToTarget;
            this.beamFromTarget = beamFromTarget;
            this.maxDistance = maxDistance;
            this.auraEffectId = auraEffectId;
            this.homingSettings = homingSettings;
            this.visualEndsAtMs = visualEndsAtMs;
            this.expiresAtMs = expiresAtMs;
            this.nextEmitAtMs = nextEmitAtMs;
        }

        private boolean useHoming() {
            return homingSettings.isEnabled() && !homingFallback && !visualsEnded;
        }

        private long emissionIntervalMs() {
            return useHoming() ? homingSettings.getSpawnIntervalMs() : LEGACY_EMIT_INTERVAL_MS;
        }
    }
}
