package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Evaluates optional claim-based breeding limits backed by SimpleClaims.
 */
final class BreedingClaimLimitPolicyService {
    private static final long WARNING_THROTTLE_MS = 60_000L;

    private final SimpleClaimsBreedingBridge simpleClaimsBridge;
    private long nextWarningAtMs;

    BreedingClaimLimitPolicyService() {
        this(SimpleClaimsBreedingBridge.initialize());
    }

    BreedingClaimLimitPolicyService(@Nullable SimpleClaimsBreedingBridge simpleClaimsBridge) {
        this.simpleClaimsBridge = simpleClaimsBridge == null
                ? SimpleClaimsBreedingBridge.initialize()
                : simpleClaimsBridge;
    }

    @Nonnull
    Decision evaluate(@Nullable Store<EntityStore> store,
                      @Nullable Vector3d spawnPosition,
                      @Nullable TwBreedingConfig config,
                      int pendingReservations) {
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveSimpleClaimsSettingsConfig();
        if (globalConfig == null || !globalConfig.isSimpleClaimsEnabled()) {
            return Decision.allowNoClaimChecks();
        }
        if (spawnPosition == null || store == null) {
            warnFailClosed("SimpleClaims breeding limit check failed: spawn/store context was missing.");
            return Decision.deny("missing-spawn-context");
        }
        String worldName = resolveWorldName(store);
        if (worldName == null || worldName.isBlank()) {
            warnFailClosed("SimpleClaims breeding limit check failed: world name was missing.");
            return Decision.deny("missing-world-name");
        }
        if (!simpleClaimsBridge.isAvailable()) {
            warnFailClosed(
                    "SimpleClaims breeding limit check failed: dependency unavailable ("
                            + simpleClaimsBridge.getUnavailableReason()
                            + ")."
            );
            return Decision.deny("simpleclaims-unavailable");
        }

        ResolvedClaim resolvedClaim = resolveClaim(worldName, spawnPosition);
        if (resolvedClaim.status() == ClaimResolutionStatus.UNAVAILABLE
                || resolvedClaim.status() == ClaimResolutionStatus.ERROR) {
            warnFailClosed(
                    "SimpleClaims breeding limit check failed: could not resolve claim context ("
                            + resolvedClaim.message()
                            + ")."
            );
            return Decision.deny("simpleclaims-lookup-error");
        }
        if (resolvedClaim.status() == ClaimResolutionStatus.NO_CLAIM) {
            if (globalConfig.isSimpleClaimsBreedingRequiresClaim()) {
                return Decision.deny("claim-required");
            }
            return Decision.allowOutsideClaim();
        }

        CountResult countResult = countBreedablePopulation(
                store,
                worldName,
                resolvedClaim.key().partyId(),
                config
        );
        if (!countResult.success()) {
            warnFailClosed(
                    "SimpleClaims breeding limit check failed: could not count claim population ("
                            + countResult.message()
                            + ")."
            );
            return Decision.deny("simpleclaims-population-count-error");
        }
        return evaluateResolved(
                globalConfig,
                resolvedClaim,
                countResult.count(),
                Math.max(0, pendingReservations)
        );
    }

    @Nonnull
    static Decision evaluateResolved(@Nonnull TwGlobalConfig globalConfig,
                                     @Nonnull ResolvedClaim resolvedClaim,
                                     int currentCount,
                                     int pendingReservations) {
        if (!globalConfig.isSimpleClaimsEnabled()) {
            return Decision.allowNoClaimChecks();
        }
        if (resolvedClaim.status() == ClaimResolutionStatus.UNAVAILABLE
                || resolvedClaim.status() == ClaimResolutionStatus.ERROR) {
            return Decision.deny("simpleclaims-lookup-error");
        }
        if (resolvedClaim.status() == ClaimResolutionStatus.NO_CLAIM) {
            if (globalConfig.isSimpleClaimsBreedingRequiresClaim()) {
                return Decision.deny("claim-required");
            }
            return Decision.allowOutsideClaim();
        }
        ClaimReservationKey claimKey = resolvedClaim.key();
        if (claimKey == null) {
            return Decision.deny("missing-claim-key");
        }
        int safeCurrent = Math.max(0, currentCount);
        int safePending = Math.max(0, pendingReservations);
        int claimChunks = Math.max(0, resolvedClaim.claimChunkCount());
        int chunkCap = globalConfig.resolveSimpleClaimsBreedingLimitPerClaimChunkCap(claimChunks);
        int totalCap = globalConfig.getSimpleClaimsBreedingLimitPerClaimTotal();
        boolean chunkCapEnabled = chunkCap > 0;
        boolean totalCapEnabled = totalCap > 0;
        if (!chunkCapEnabled && !totalCapEnabled) {
            return Decision.allowInsideClaimWithoutCap(claimKey);
        }
        int effectiveCap = Integer.MAX_VALUE;
        if (chunkCapEnabled) {
            effectiveCap = Math.min(effectiveCap, chunkCap);
        }
        if (totalCapEnabled) {
            effectiveCap = Math.min(effectiveCap, totalCap);
        }
        if (effectiveCap == Integer.MAX_VALUE) {
            return Decision.allowInsideClaimWithoutCap(claimKey);
        }
        int remaining = effectiveCap - safeCurrent - safePending;
        if (remaining <= 0) {
            return Decision.denyAtCap(claimKey, effectiveCap, safeCurrent, safePending);
        }
        return Decision.allowWithCap(claimKey, effectiveCap, safeCurrent, safePending, remaining);
    }

    @Nonnull
    ResolvedClaim resolveClaim(@Nullable String worldName, @Nullable Vector3d position) {
        if (worldName == null || worldName.isBlank() || position == null) {
            return new ResolvedClaim(ClaimResolutionStatus.ERROR, null, 0, "missing-world-or-position");
        }
        SimpleClaimsBreedingBridge.LookupResult lookup = simpleClaimsBridge.lookupClaim(worldName, position);
        if (lookup == null) {
            return new ResolvedClaim(ClaimResolutionStatus.ERROR, null, 0, "lookup-result-null");
        }
        return switch (lookup.status()) {
            case CLAIM_FOUND -> {
                SimpleClaimsBreedingBridge.ClaimInfo claimInfo = lookup.claimInfo();
                if (claimInfo == null || claimInfo.partyId() == null) {
                    yield new ResolvedClaim(ClaimResolutionStatus.ERROR, null, 0, "claim-info-missing");
                }
                yield new ResolvedClaim(
                        ClaimResolutionStatus.CLAIM_FOUND,
                        new ClaimReservationKey(worldName, claimInfo.partyId()),
                        Math.max(0, claimInfo.claimChunkCount()),
                        null
                );
            }
            case NO_CLAIM -> new ResolvedClaim(ClaimResolutionStatus.NO_CLAIM, null, 0, null);
            case UNAVAILABLE -> new ResolvedClaim(ClaimResolutionStatus.UNAVAILABLE, null, 0, lookup.message());
            case ERROR -> new ResolvedClaim(ClaimResolutionStatus.ERROR, null, 0, lookup.message());
        };
    }

    @Nonnull
    private CountResult countBreedablePopulation(@Nonnull Store<EntityStore> store,
                                                 @Nonnull String worldName,
                                                 @Nonnull UUID claimPartyId,
                                                 @Nullable TwBreedingConfig config) {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (npcType == null || transformType == null) {
            return new CountResult(false, 0, "required component types unavailable");
        }

        int[] count = new int[] {0};
        boolean[] failed = new boolean[] {false};
        String[] failureMessage = new String[] {null};
        Map<String, ResolvedClaim> claimLookupCache = new HashMap<>();

        store.forEachChunk(
                Query.and(npcType, transformType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        countChunkPopulation(
                                chunk,
                                store,
                                npcType,
                                transformType,
                                breedingType,
                                worldName,
                                claimPartyId,
                                config,
                                count,
                                failed,
                                failureMessage,
                                claimLookupCache
                        )
        );

        if (failed[0]) {
            return new CountResult(false, 0, failureMessage[0]);
        }
        return new CountResult(true, count[0], null);
    }

    private void countChunkPopulation(@Nonnull ArchetypeChunk<EntityStore> chunk,
                                      @Nonnull Store<EntityStore> store,
                                      @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                                      @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
                                      @Nullable ComponentType<EntityStore, TameworkBreedingComponent> breedingType,
                                      @Nonnull String worldName,
                                      @Nonnull UUID claimPartyId,
                                      @Nullable TwBreedingConfig config,
                                      @Nonnull int[] count,
                                      @Nonnull boolean[] failed,
                                      @Nonnull String[] failureMessage,
                                      @Nonnull Map<String, ResolvedClaim> claimLookupCache) {
        if (failed[0]) {
            return;
        }
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            if (failed[0]) {
                return;
            }
            Ref<EntityStore> ref = chunk.getReferenceTo(i);
            NPCEntity npc = chunk.getComponent(i, npcType);
            TransformComponent transform = chunk.getComponent(i, transformType);
            TameworkBreedingComponent breeding = breedingType != null
                    ? chunk.getComponent(i, breedingType)
                    : null;
            if (ref == null
                    || !ref.isValid()
                    || npc == null
                    || transform == null) {
                continue;
            }
            TwBreedingConfig resolvedConfig = BreedingConfigResolver.resolveConfig(ref, store, breeding);
            TwBreedingConfig effectiveConfig = resolvedConfig != null ? resolvedConfig : config;
            if (!isCountableBreedable(breeding, effectiveConfig)) {
                continue;
            }
            Vector3d position = transform.getPosition();
            String cacheKey = chunkCacheKey(worldName, position);
            ResolvedClaim resolvedClaim = claimLookupCache.get(cacheKey);
            if (resolvedClaim == null) {
                resolvedClaim = resolveClaim(worldName, position);
                claimLookupCache.put(cacheKey, resolvedClaim);
            }
            if (resolvedClaim.status() == ClaimResolutionStatus.UNAVAILABLE
                    || resolvedClaim.status() == ClaimResolutionStatus.ERROR) {
                failed[0] = true;
                failureMessage[0] = resolvedClaim.message();
                return;
            }
            if (resolvedClaim.status() != ClaimResolutionStatus.CLAIM_FOUND || resolvedClaim.key() == null) {
                continue;
            }
            if (claimPartyId.equals(resolvedClaim.key().partyId())) {
                count[0]++;
            }
        }
    }

    @Nullable
    private static String resolveWorldName(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        return world != null ? world.getName() : null;
    }

    @Nonnull
    private static String chunkCacheKey(@Nonnull String worldName, @Nonnull Vector3d position) {
        int blockX = (int) Math.floor(position.x);
        int blockZ = (int) Math.floor(position.z);
        int chunkX = ChunkUtil.chunkCoordinate(blockX);
        int chunkZ = ChunkUtil.chunkCoordinate(blockZ);
        return worldName + "|" + chunkX + "|" + chunkZ;
    }

    static boolean isCountableBreedable(@Nullable TameworkBreedingComponent breeding,
                                        @Nullable TwBreedingConfig effectiveConfig) {
        if (breeding != null && breeding.isEnabled()) {
            return true;
        }
        return effectiveConfig != null && effectiveConfig.isEnabled();
    }

    private void warnFailClosed(@Nullable String warning) {
        if (warning == null || warning.isBlank()) {
            return;
        }
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextWarningAtMs) {
            return;
        }
        nextWarningAtMs = now + WARNING_THROTTLE_MS;
        plugin.getLogger().at(Level.WARNING).log(warning);
    }

    enum ClaimResolutionStatus {
        CLAIM_FOUND,
        NO_CLAIM,
        UNAVAILABLE,
        ERROR
    }

    record ClaimReservationKey(String worldName, UUID partyId) {
    }

    record ResolvedClaim(ClaimResolutionStatus status,
                         @Nullable ClaimReservationKey key,
                         int claimChunkCount,
                         @Nullable String message) {
    }

    record Decision(boolean allowed,
                    boolean capEnforced,
                    int effectiveCap,
                    int currentCount,
                    int pendingReservations,
                    int remainingHeadroom,
                    @Nullable ClaimReservationKey claimReservationKey,
                    String reason) {
        static Decision allowNoClaimChecks() {
            return new Decision(true, false, 0, 0, 0, Integer.MAX_VALUE, null, "claims-disabled");
        }

        static Decision allowOutsideClaim() {
            return new Decision(true, false, 0, 0, 0, Integer.MAX_VALUE, null, "outside-claim");
        }

        static Decision allowInsideClaimWithoutCap(@Nonnull ClaimReservationKey claimKey) {
            return new Decision(true, false, 0, 0, 0, Integer.MAX_VALUE, claimKey, "claim-no-cap");
        }

        static Decision allowWithCap(@Nonnull ClaimReservationKey claimKey,
                                     int effectiveCap,
                                     int currentCount,
                                     int pendingReservations,
                                     int remainingHeadroom) {
            return new Decision(
                    true,
                    true,
                    Math.max(0, effectiveCap),
                    Math.max(0, currentCount),
                    Math.max(0, pendingReservations),
                    Math.max(0, remainingHeadroom),
                    claimKey,
                    "claim-cap-allow"
            );
        }

        static Decision denyAtCap(@Nonnull ClaimReservationKey claimKey,
                                  int effectiveCap,
                                  int currentCount,
                                  int pendingReservations) {
            return new Decision(
                    false,
                    true,
                    Math.max(0, effectiveCap),
                    Math.max(0, currentCount),
                    Math.max(0, pendingReservations),
                    0,
                    claimKey,
                    "claim-cap-reached"
            );
        }

        static Decision deny(@Nonnull String reason) {
            return new Decision(false, false, 0, 0, 0, 0, null, reason);
        }
    }

    private record CountResult(boolean success, int count, @Nullable String message) {
    }
}
