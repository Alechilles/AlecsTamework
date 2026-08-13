package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.damage.SimpleClaimsCapabilityRuntime;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Evaluates released SimpleClaims owner-population limits without a second durable authority.
 */
final class BreedingClaimLimitPolicyService {
    private final SimpleClaimsCapabilityRuntime capabilityRuntime;

    BreedingClaimLimitPolicyService() {
        this(resolveCapabilityRuntime());
    }

    BreedingClaimLimitPolicyService(
            @Nonnull SimpleClaimsCapabilityRuntime capabilityRuntime
    ) {
        this.capabilityRuntime = capabilityRuntime;
    }

    /**
     * Evaluates the released SimpleClaims cap and the process-local owner cap.
     *
     * <p>Both counts are live reads. No reservation or replay state is created.
     */
    @Nonnull
    Decision evaluate(@Nullable Store<EntityStore> store,
                      @Nullable Vector3d spawnPosition,
                      @Nullable TwBreedingConfig config,
                      @Nullable String inheritanceRoleId,
                      @Nullable UUID parentAOwnerId,
                      @Nullable UUID parentBOwnerId) {
        return evaluate(
                store,
                spawnPosition,
                config,
                inheritanceRoleId,
                parentAOwnerId,
                parentBOwnerId,
                null,
                null
        );
    }

    @Nonnull
    Decision evaluate(
            @Nullable Store<EntityStore> store,
            @Nullable Vector3d spawnPosition,
            @Nullable TwBreedingConfig config,
            @Nullable String inheritanceRoleId,
            @Nullable UUID parentAOwnerId,
            @Nullable UUID parentBOwnerId,
            @Nullable Map<ClaimReservationKey, Integer> pendingClaims,
            @Nullable Map<PlayerReservationKey, Integer> pendingOwners
    ) {
        Decision claimDecision = evaluateLiveClaim(
                store, spawnPosition, pendingClaims
        );
        if (!claimDecision.allowed()) {
            return claimDecision;
        }
        BreedingOffspringProgressionService.OwnerSnapshot inheritedOwner =
                BreedingInheritedOwnerResolver.resolve(
                        config,
                        inheritanceRoleId,
                        new BreedingOffspringProgressionService.OwnerSnapshot(
                                parentAOwnerId, null
                        ),
                        new BreedingOffspringProgressionService.OwnerSnapshot(
                                parentBOwnerId, null
                        )
                );
        Decision ownerDecision = evaluateLiveOwner(
                store,
                inheritedOwner.ownerId() == null
                        ? List.of()
                        : List.of(inheritedOwner.ownerId()),
                pendingOwners
        );
        if (!ownerDecision.allowed()) {
            return ownerDecision;
        }
        return combineAllowed(claimDecision, ownerDecision);
    }

    @Nonnull
    static Decision combineAllowed(@Nonnull Decision claimDecision,
                                   @Nonnull Decision ownerDecision) {
        if (!claimDecision.capEnforced()) {
            return ownerDecision.capEnforced() ? ownerDecision : claimDecision;
        }
        if (!ownerDecision.capEnforced()) {
            return claimDecision;
        }
        Decision limiting = claimDecision.remainingHeadroom()
                <= ownerDecision.remainingHeadroom()
                ? claimDecision
                : ownerDecision;
        return new Decision(
                true,
                true,
                limiting.effectiveCap(),
                limiting.currentCount(),
                limiting.pendingReservations(),
                limiting.remainingHeadroom(),
                claimDecision.claimReservationKey(),
                ownerDecision.playerReservationKeys(),
                limiting.reason()
        );
    }

    @Nonnull
    private Decision evaluateLiveClaim(
            @Nullable Store<EntityStore> store,
            @Nullable Vector3d spawnPosition,
            @Nullable Map<ClaimReservationKey, Integer> pendingClaims
    ) {
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveSimpleClaimsSettingsConfig();
        if (globalConfig == null) {
            globalConfig = TwGlobalConfig.resolveActive();
        }
        if (globalConfig == null) {
            globalConfig = TwGlobalConfig.defaultConfig();
        }
        if (!claimPolicyEnabled(globalConfig)) {
            return Decision.allowNoPopulationChecks();
        }
        String worldName = resolveWorldName(store);
        if (store == null || spawnPosition == null || worldName == null) {
            return Decision.deny("missing-spawn-context");
        }
        SimpleClaimsCapabilityRuntime.BridgeResolution bridgeResolution = resolveBridge();
        if (!bridgeResolution.available()) {
            return evaluateResolved(
                    globalConfig,
                    new ResolvedClaim(
                            ClaimResolutionStatus.UNAVAILABLE,
                            null,
                            0,
                            bridgeResolution.reason()
                    ),
                    0,
                    0
            );
        }
        SimpleClaimsBreedingBridge bridge = bridgeResolution.bridge();
        ResolvedClaim resolved = resolveClaim(bridge, worldName, spawnPosition);
        if (resolved.status() != ClaimResolutionStatus.CLAIM_FOUND
                || resolved.key() == null) {
            return evaluateResolved(globalConfig, resolved, 0, 0);
        }
        CountResult count = countOwnedPopulationInClaim(
                bridge, store, worldName, resolved.key().partyId()
        );
        int pending = pendingClaims == null
                ? 0
                : pendingClaims.getOrDefault(resolved.key(), 0);
        return count.success()
                ? evaluateResolved(globalConfig, resolved, count.count(), pending)
                : Decision.deny("simpleclaims-population-count-error");
    }

    @Nonnull
    private static Decision evaluateLiveOwner(
            @Nullable Store<EntityStore> store,
            @Nonnull List<UUID> ownerTargets,
            @Nullable Map<PlayerReservationKey, Integer> pendingOwners
    ) {
        if (ownerTargets.isEmpty()) {
            return Decision.allowWithoutCap(null, List.of(), "owner-cap-no-owner");
        }
        OwnerPopulationCapService.Decision ownerDecision =
                OwnerPopulationCapService.evaluateAcquisition(store, ownerTargets.get(0));
        if (!ownerDecision.allowed()) {
            return new Decision(
                    false,
                    ownerDecision.capEnabled(),
                    ownerDecision.limit(),
                    ownerDecision.currentCount(),
                    0,
                    0,
                    null,
                    List.of(),
                    ownerDecision.reason()
            );
        }
        if (!ownerDecision.capEnabled()) {
            return Decision.allowWithoutCap(null, List.of(), ownerDecision.reason());
        }
        PlayerReservationKey key = ownerDecision.scope()
                == TwGlobalConfig.PerPlayerLimitScope.GLOBAL
                ? PlayerReservationKey.global(ownerTargets.get(0))
                : PlayerReservationKey.perWorld(resolveWorldName(store), ownerTargets.get(0));
        int pending = pendingOwners == null
                ? 0
                : Math.max(0, pendingOwners.getOrDefault(key, 0));
        int remaining = Math.max(0, ownerDecision.remainingHeadroom() - pending);
        return new Decision(
                remaining > 0,
                true,
                ownerDecision.limit(),
                ownerDecision.currentCount(),
                pending,
                remaining,
                null,
                List.of(key),
                remaining > 0 ? ownerDecision.reason() : "owner-cap-reached"
        );
    }

    @Nonnull
    ResolvedClaim resolveClaim(@Nullable String worldName, @Nullable Vector3d position) {
        if (worldName == null || worldName.isBlank() || position == null) {
            return new ResolvedClaim(
                    ClaimResolutionStatus.ERROR, null, 0, "missing-world-or-position"
            );
        }
        SimpleClaimsCapabilityRuntime.BridgeResolution resolution = resolveBridge();
        if (!resolution.available()) {
            return new ResolvedClaim(
                    ClaimResolutionStatus.UNAVAILABLE, null, 0, resolution.reason()
            );
        }
        return resolveClaim(resolution.bridge(), worldName, position);
    }

    @Nonnull
    SimpleClaimsCapabilityRuntime.BridgeResolution resolveBridge() {
        return capabilityRuntime.resolveBridge();
    }

    @Nonnull
    static ResolvedClaim resolveClaim(
            @Nonnull SimpleClaimsBreedingBridge bridge,
            @Nonnull String worldName,
            @Nonnull Vector3d position
    ) {
        SimpleClaimsBreedingBridge.LookupResult lookup =
                bridge.lookupSimpleClaimsClaim(worldName, position);
        if (lookup == null) {
            return new ResolvedClaim(
                    ClaimResolutionStatus.ERROR, null, 0, "lookup-result-null"
            );
        }
        return mapLookup(worldName, lookup);
    }

    @Nonnull
    CountResult countOwnedPopulationInClaim(@Nonnull Store<EntityStore> store,
                                            @Nonnull String worldName,
                                            @Nonnull ClaimReservationKey targetClaim) {
        return countOwnedPopulationInClaim(
                store, worldName, targetClaim.partyId()
        );
    }

    @Nonnull
    CountResult countOwnedPopulationInClaim(
            @Nonnull Store<EntityStore> store,
            @Nonnull String worldName,
            @Nonnull UUID claimPartyId
    ) {
        SimpleClaimsCapabilityRuntime.BridgeResolution resolution = resolveBridge();
        if (!resolution.available()) {
            return new CountResult(false, 0, resolution.reason());
        }
        return countOwnedPopulationInClaim(
                resolution.bridge(), store, worldName, claimPartyId
        );
    }

    @Nonnull
    CountResult countOwnedPopulationInClaim(
            @Nonnull SimpleClaimsBreedingBridge bridge,
            @Nonnull Store<EntityStore> store,
            @Nonnull String worldName,
            @Nonnull UUID claimPartyId
    ) {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType =
                TransformComponent.getComponentType();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (npcType == null || transformType == null || ownerType == null) {
            return new CountResult(false, 0, "required component types unavailable");
        }
        ClaimCount count = new ClaimCount();
        Map<String, ResolvedClaim> cache = new HashMap<>();
        store.forEachChunk(
                Query.and(npcType, transformType, ownerType),
                (ArchetypeChunk<EntityStore> chunk,
                 CommandBuffer<EntityStore> commandBuffer) -> countChunk(
                        chunk, npcType, transformType, ownerType,
                        bridge, worldName, claimPartyId, count, cache
                )
        );
        return count.failure == null
                ? new CountResult(true, count.value, null)
                : new CountResult(false, 0, count.failure);
    }

    private void countChunk(
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull SimpleClaimsBreedingBridge bridge,
            @Nonnull String worldName,
            @Nonnull UUID claimPartyId,
            @Nonnull ClaimCount count,
            @Nonnull Map<String, ResolvedClaim> cache
    ) {
        if (count.failure != null) {
            return;
        }
        for (int index = 0; index < chunk.size(); index++) {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            NPCEntity npc = chunk.getComponent(index, npcType);
            TransformComponent transform = chunk.getComponent(index, transformType);
            TameworkOwnerComponent owner = chunk.getComponent(index, ownerType);
            if (ref == null || !ref.isValid() || npc == null || transform == null
                    || owner == null || owner.getOwnerId() == null) {
                continue;
            }
            Vector3d position = transform.getPosition();
            if (position == null) {
                continue;
            }
            ResolvedClaim claim = cache.computeIfAbsent(
                    chunkCacheKey(worldName, position),
                    ignored -> resolveClaim(bridge, worldName, position)
            );
            if (claim.status() == ClaimResolutionStatus.UNAVAILABLE
                    || claim.status() == ClaimResolutionStatus.ERROR) {
                count.failure = claim.message() == null
                        ? "claim lookup failed"
                        : claim.message();
                return;
            }
            if (claim.status() == ClaimResolutionStatus.CLAIM_FOUND
                    && claim.key() != null
                    && claimPartyId.equals(claim.key().partyId())) {
                count.value++;
            }
        }
    }

    @Nonnull
    static Decision evaluateResolved(@Nonnull TwGlobalConfig globalConfig,
                                     @Nonnull ResolvedClaim resolvedClaim,
                                     int currentCount,
                                     int pendingReservations) {
        if (!claimPolicyEnabled(globalConfig)) {
            return Decision.allowNoPopulationChecks();
        }
        if (resolvedClaim.status() == ClaimResolutionStatus.UNAVAILABLE
                || resolvedClaim.status() == ClaimResolutionStatus.ERROR) {
            return Decision.deny("simpleclaims-lookup-error");
        }
        if (resolvedClaim.status() == ClaimResolutionStatus.NO_CLAIM) {
            if (TameworkRuntimeSettings.simpleClaimsBreedingRequiresClaim(
                    globalConfig.isSimpleClaimsBreedingRequiresClaim()
            )) {
                return Decision.deny("claim-required");
            }
            return Decision.allowWithoutCap(null, List.of(), "outside-claim");
        }
        ClaimReservationKey claimKey = resolvedClaim.key();
        if (claimKey == null) {
            return Decision.deny("missing-claim-key");
        }
        ConstraintState constraint = evaluateClaimConstraint(
                globalConfig, resolvedClaim, currentCount, pendingReservations
        );
        if (constraint == null) {
            return Decision.allowWithoutCap(claimKey, List.of(), "claim-no-cap");
        }
        return constraint.remainingHeadroom() <= 0
                ? Decision.denyAtCap(
                constraint.type(), constraint.effectiveCap(), constraint.currentCount(),
                constraint.pendingReservations(), claimKey, List.of()
        )
                : Decision.allowWithCap(
                constraint.type(), constraint.effectiveCap(), constraint.currentCount(),
                constraint.pendingReservations(), constraint.remainingHeadroom(), claimKey, List.of()
        );
    }

    @Nullable
    static ConstraintState evaluateClaimConstraint(@Nonnull TwGlobalConfig globalConfig,
                                                   @Nonnull ResolvedClaim resolvedClaim,
                                                   int currentCount,
                                                   int pendingReservations) {
        if (!claimPolicyEnabled(globalConfig)
                || resolvedClaim.status() != ClaimResolutionStatus.CLAIM_FOUND
                || resolvedClaim.key() == null) {
            return null;
        }
        int perChunk = TameworkRuntimeSettings.simpleClaimsLimitPerClaimChunk(
                globalConfig.getSimpleClaimsBreedingLimitPerClaimChunk()
        );
        int total = TameworkRuntimeSettings.simpleClaimsLimitPerClaimTotal(
                globalConfig.getSimpleClaimsBreedingLimitPerClaimTotal()
        );
        int chunkCap = multiplyCap(perChunk, resolvedClaim.claimChunkCount());
        if (chunkCap <= 0 && total <= 0) {
            return null;
        }
        int effectiveCap = chunkCap > 0 && total > 0
                ? Math.min(chunkCap, total)
                : Math.max(chunkCap, total);
        int current = Math.max(0, currentCount);
        int pending = Math.max(0, pendingReservations);
        return new ConstraintState(
                ConstraintType.CLAIM,
                effectiveCap,
                current,
                pending,
                Math.max(0, effectiveCap - current - pending)
        );
    }

    private static boolean claimPolicyEnabled(@Nonnull TwGlobalConfig config) {
        return TameworkRuntimeSettings.simpleClaimsEnabled(config.isSimpleClaimsEnabled());
    }

    @Nonnull
    private static SimpleClaimsCapabilityRuntime resolveCapabilityRuntime() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null
                ? new SimpleClaimsCapabilityRuntime()
                : plugin.getSimpleClaimsCapabilityRuntime();
    }

    private static int multiplyCap(int perChunk, int claimChunkCount) {
        if (perChunk <= 0) {
            return 0;
        }
        long result = (long) perChunk * Math.max(0, claimChunkCount);
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    @Nonnull
    private static ResolvedClaim mapLookup(
            @Nonnull String worldName,
            @Nonnull SimpleClaimsBreedingBridge.LookupResult lookup
    ) {
        return switch (lookup.status()) {
            case CLAIM_FOUND -> {
                SimpleClaimsBreedingBridge.ClaimInfo info = lookup.claimInfo();
                if (info == null || info.partyId() == null) {
                    yield new ResolvedClaim(
                            ClaimResolutionStatus.ERROR, null, 0, "claim-info-missing"
                    );
                }
                yield new ResolvedClaim(
                        ClaimResolutionStatus.CLAIM_FOUND,
                        new ClaimReservationKey(worldName, info.partyId()),
                        info.claimChunkCount(),
                        lookup.message()
                );
            }
            case NO_CLAIM -> new ResolvedClaim(
                    ClaimResolutionStatus.NO_CLAIM, null, 0, lookup.message()
            );
            case UNAVAILABLE -> new ResolvedClaim(
                    ClaimResolutionStatus.UNAVAILABLE, null, 0, lookup.message()
            );
            case ERROR -> new ResolvedClaim(
                    ClaimResolutionStatus.ERROR, null, 0, lookup.message()
            );
        };
    }

    @Nonnull
    private static String chunkCacheKey(@Nonnull String worldName, @Nonnull Vector3d position) {
        int chunkX = ChunkUtil.chunkCoordinate((int) Math.floor(position.x));
        int chunkZ = ChunkUtil.chunkCoordinate((int) Math.floor(position.z));
        return worldName + "|" + chunkX + "|" + chunkZ;
    }

    @Nullable
    private static String resolveWorldName(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        if (world == null || world.getName() == null || world.getName().isBlank()) {
            return null;
        }
        return world.getName().trim();
    }

    enum ClaimResolutionStatus {
        CLAIM_FOUND,
        NO_CLAIM,
        UNAVAILABLE,
        ERROR
    }

    enum ConstraintType {
        CLAIM("claim-cap-allow", "claim-cap-reached");

        private final String allowReason;
        private final String denyReason;

        ConstraintType(@Nonnull String allowReason, @Nonnull String denyReason) {
            this.allowReason = allowReason;
            this.denyReason = denyReason;
        }
    }

    record ClaimReservationKey(String worldName, UUID partyId) {
    }

    record PlayerReservationKey(TwGlobalConfig.PerPlayerLimitScope scope,
                                @Nullable String worldName,
                                UUID ownerId) {
        PlayerReservationKey {
            scope = scope == null ? TwGlobalConfig.PerPlayerLimitScope.PER_WORLD : scope;
            if (scope == TwGlobalConfig.PerPlayerLimitScope.GLOBAL) {
                worldName = null;
            }
        }

        @Nonnull
        static PlayerReservationKey perWorld(@Nonnull String worldName, @Nonnull UUID ownerId) {
            return new PlayerReservationKey(TwGlobalConfig.PerPlayerLimitScope.PER_WORLD, worldName, ownerId);
        }

        @Nonnull
        static PlayerReservationKey global(@Nonnull UUID ownerId) {
            return new PlayerReservationKey(TwGlobalConfig.PerPlayerLimitScope.GLOBAL, null, ownerId);
        }
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
                    @Nonnull List<PlayerReservationKey> playerReservationKeys,
                    @Nonnull String reason) {
        Decision {
            playerReservationKeys = playerReservationKeys == null
                    ? List.of()
                    : List.copyOf(playerReservationKeys);
        }

        static Decision allowNoPopulationChecks() {
            return allowWithoutCap(null, List.of(), "population-caps-disabled");
        }

        static Decision allowWithoutCap(@Nullable ClaimReservationKey claimKey,
                                        @Nonnull List<PlayerReservationKey> playerKeys,
                                        @Nonnull String reason) {
            return new Decision(true, false, 0, 0, 0, Integer.MAX_VALUE,
                    claimKey, playerKeys, reason);
        }

        static Decision allowWithCap(@Nonnull ConstraintType type,
                                     int effectiveCap,
                                     int currentCount,
                                     int pendingReservations,
                                     int remainingHeadroom,
                                     @Nullable ClaimReservationKey claimKey,
                                     @Nonnull List<PlayerReservationKey> playerKeys) {
            return new Decision(true, true, Math.max(0, effectiveCap), Math.max(0, currentCount),
                    Math.max(0, pendingReservations), Math.max(0, remainingHeadroom),
                    claimKey, playerKeys, type.allowReason);
        }

        static Decision denyAtCap(@Nonnull ConstraintType type,
                                  int effectiveCap,
                                  int currentCount,
                                  int pendingReservations,
                                  @Nullable ClaimReservationKey claimKey,
                                  @Nonnull List<PlayerReservationKey> playerKeys) {
            return new Decision(false, true, Math.max(0, effectiveCap), Math.max(0, currentCount),
                    Math.max(0, pendingReservations), 0, claimKey, playerKeys, type.denyReason);
        }

        static Decision deny(@Nonnull String reason) {
            return new Decision(false, false, 0, 0, 0, 0, null, List.of(), reason);
        }
    }

    record ConstraintState(ConstraintType type,
                           int effectiveCap,
                           int currentCount,
                           int pendingReservations,
                           int remainingHeadroom) {
    }

    record CountResult(boolean success, int count, @Nullable String message) {
    }

    private static final class ClaimCount {
        private int value;
        private String failure;
    }
}
