package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService;
import com.alechilles.alecstamework.npc.progression.PositionTargetRejectCache;
import com.alechilles.alecstamework.npc.progression.PositionTargetReservationCache;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Owns the shared state operations used by needs-resource target sensors and actions.
 *
 * <p>The facade keeps cache and rejection state outside the sensor instance. The public sensor
 * methods remain compatibility entry points for NPC actions and other Tamework integrations.</p>
 */
final class NeedsResourceTargetStateFacade {
    private static final NeedsResourcePathPreflightService PATH_PREFLIGHT_SERVICE =
            NeedsResourcePathPreflightService.shared();
    private static final double PREFLIGHT_REJECT_TTL_SECONDS = 4.0;

    private NeedsResourceTargetStateFacade() {
    }

    static boolean rejectTarget(@Nullable UUID npcUuid,
                                @Nullable String resourceType,
                                @Nullable Vector3d target,
                                double suppressSeconds) {
        return rejectTarget(npcUuid, null, resourceType, target, suppressSeconds);
    }

    static boolean rejectTarget(@Nullable UUID npcUuid,
                                @Nullable String worldName,
                                @Nullable String resourceType,
                                @Nullable Vector3d target,
                                double suppressSeconds) {
        invalidatePreflightLeases(npcUuid, worldName, resourceType, target);
        return NeedsResourceTargetCacheAdapter.rejectTarget(npcUuid, resourceType, target, suppressSeconds);
    }

    static boolean rejectTargetForTests(@Nullable UUID npcUuid,
                                        @Nullable String resourceType,
                                        @Nullable Vector3d target,
                                        double suppressSeconds,
                                        long nowMs) {
        invalidatePreflightLeases(npcUuid, null, resourceType, target);
        if (resourceType == null || resourceType.isBlank() || "auto".equalsIgnoreCase(resourceType.trim())) {
            boolean water = NeedsResourceTargetCacheAdapter.rejectTarget(
                    npcUuid, "water", target, suppressSeconds, nowMs
            );
            boolean food = NeedsResourceTargetCacheAdapter.rejectTarget(
                    npcUuid, "food_container", target, suppressSeconds, nowMs
            );
            return water || food;
        }
        return NeedsResourceTargetCacheAdapter.rejectTarget(
                npcUuid, resourceType, target, suppressSeconds, nowMs
        );
    }

    private static void invalidatePreflightLeases(@Nullable UUID npcUuid,
                                                  @Nullable String worldName,
                                                  @Nullable String resourceType,
                                                  @Nullable Vector3d target) {
        if (resourceType == null || resourceType.isBlank() || "auto".equalsIgnoreCase(resourceType.trim())) {
            PATH_PREFLIGHT_SERVICE.invalidateTarget(npcUuid, worldName, "Water", target);
            PATH_PREFLIGHT_SERVICE.invalidateTarget(npcUuid, worldName, "FoodContainer", target);
            return;
        }
        String normalized = resourceType.trim().toLowerCase(Locale.ROOT);
        String label = normalized.equals("food")
                || normalized.equals("foodcontainer")
                || normalized.equals("food_container")
                ? "FoodContainer"
                : "Water";
        PATH_PREFLIGHT_SERVICE.invalidateTarget(npcUuid, worldName, label, target);
    }

    static boolean isTargetRejectedForTests(@Nullable UUID npcUuid,
                                            @Nullable String resourceType,
                                            @Nullable Vector3d target,
                                            long nowMs) {
        return NeedsResourceTargetCacheAdapter.isTargetRejected(npcUuid, resourceType, target, nowMs);
    }

    static void clearRejectedTargetsForTests() {
        PositionTargetRejectCache.clearForTests();
    }

    static int rejectedTargetCountForTests() {
        return PositionTargetRejectCache.countForTests();
    }

    static int rejectedTargetMaxEntriesForTests() {
        return PositionTargetRejectCache.MAX_ENTRIES;
    }

    static boolean reserveTargetForTests(@Nullable UUID npcUuid,
                                         @Nullable String worldName,
                                         @Nullable String resourceType,
                                         @Nullable Vector3d target,
                                         long nowMs) {
        return NeedsResourceTargetCacheAdapter.reserveTarget(
                npcUuid, worldName, resourceType, target, nowMs
        );
    }

    static boolean isTargetReservedByOtherForTests(@Nullable UUID npcUuid,
                                                   @Nullable String worldName,
                                                   @Nullable String resourceType,
                                                   @Nullable Vector3d target,
                                                   long nowMs) {
        return NeedsResourceTargetCacheAdapter.isReservedByOther(
                npcUuid, worldName, resourceType, target, nowMs
        );
    }

    static void releaseTargetForTests(@Nullable UUID npcUuid,
                                      @Nullable String worldName,
                                      @Nullable String resourceType,
                                      @Nullable Vector3d target) {
        NeedsResourceTargetCacheAdapter.releaseTarget(npcUuid, worldName, resourceType, target);
    }

    static void clearTargetReservationsForTests() {
        PositionTargetReservationCache.clearForTests();
    }

    static void rememberFastConsumeTargetForTests(@Nullable UUID npcUuid,
                                                  @Nullable String worldName,
                                                  @Nullable String resourceType,
                                                  @Nullable Vector3d target,
                                                  long expiresAtMs) {
        NeedsResourceTargetCacheAdapter.rememberFastConsumeTargetForTests(
                npcUuid, worldName, resourceType, target, expiresAtMs
        );
    }

    static boolean isFastConsumeTargetForTests(@Nullable UUID npcUuid,
                                               @Nullable String worldName,
                                               @Nullable String resourceType,
                                               @Nullable Vector3d target,
                                               long nowMs) {
        return NeedsResourceTargetCacheAdapter.isFastConsumeTargetForTests(
                npcUuid, worldName, resourceType, target, nowMs
        );
    }

    static void clearFastConsumeTargetsForTests() {
        NeedsResourceTargetCacheAdapter.clearFastConsumeTargetsForTests();
    }

    static long targetCacheTtlMs(boolean hasTarget) {
        return NeedsResourceTargetCacheAdapter.targetCacheTtlMs(hasTarget);
    }

    static double preflightRejectTtlSecondsForTests() {
        return PREFLIGHT_REJECT_TTL_SECONDS;
    }

    static double preflightRejectTtlSeconds() {
        return PREFLIGHT_REJECT_TTL_SECONDS;
    }

    static boolean shouldBypassPathPreflightForTests(boolean fastModeActive, boolean hasTarget) {
        return fastModeActive && hasTarget;
    }

    @Nonnull
    static String fastModeReasonForTests(@Nonnull String reason) {
        return reason.endsWith("_fast_consume") ? reason : reason + "_fast_consume";
    }

    static boolean targetCacheBlockMatchesForTests(@Nonnull Vector3d cachedScanPosition,
                                                   @Nonnull Vector3d currentPosition) {
        return block(cachedScanPosition.x) == block(currentPosition.x)
                && block(cachedScanPosition.y) == block(currentPosition.y)
                && block(cachedScanPosition.z) == block(currentPosition.z);
    }

    static void releaseTarget(@Nullable Ref<EntityStore> npcRef,
                              @Nullable Store<EntityStore> store,
                              @Nullable String resourceType,
                              @Nullable Vector3d target) {
        NeedsResourceTargetCacheAdapter.releaseTarget(npcRef, store, resourceType, target);
    }

    static boolean hasFastConsumeTarget(@Nullable Ref<EntityStore> npcRef,
                                        @Nullable Store<EntityStore> store,
                                        long nowMs) {
        return NeedsResourceTargetCacheAdapter.hasFastConsumeTarget(npcRef, store, nowMs);
    }

    private static int block(double value) {
        return (int) Math.floor(value);
    }
}

/** Describes the result of the needs threshold gate before resource lookup begins. */
record SearchEligibility(boolean allowed,
                         @Nonnull String reason,
                         double currentRatio,
                         @Nullable TwNeedsConfig needsConfig) {
    @Nonnull
    static SearchEligibility allowed(double ratio,
                                     @Nullable TwNeedsConfig config) {
        return new SearchEligibility(true, "eligible", ratio, config);
    }

    @Nonnull
    static SearchEligibility blocked(@Nonnull String reason,
                                     double ratio,
                                     @Nullable TwNeedsConfig config) {
        return new SearchEligibility(false, reason, ratio, config);
    }
}
