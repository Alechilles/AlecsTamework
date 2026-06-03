package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
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
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Evaluates optional population-based breeding caps (SimpleClaims + per-player).
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
                      @Nullable String inheritanceRoleId,
                      @Nullable UUID parentAOwnerId,
                      @Nullable UUID parentBOwnerId,
                      @Nullable Map<ClaimReservationKey, Integer> pendingClaimReservations,
                      @Nullable Map<PlayerReservationKey, Integer> pendingPlayerReservations) {
        TwGlobalConfig activeGlobalConfig = TwGlobalConfig.resolveActive();
        if (activeGlobalConfig == null) {
            activeGlobalConfig = TwGlobalConfig.defaultConfig();
        }
        TwGlobalConfig simpleClaimsConfig = TwGlobalConfig.resolveSimpleClaimsSettingsConfig();
        if (simpleClaimsConfig == null) {
            simpleClaimsConfig = activeGlobalConfig;
        }

        boolean simpleClaimsEnabled = TameworkRuntimeSettings.simpleClaimsEnabled(simpleClaimsConfig.isSimpleClaimsEnabled());
        int perPlayerLimit = TameworkRuntimeSettings.populationLimitPerPlayerOwnedTotal(
                activeGlobalConfig.getPopulationLimitPerPlayerOwnedTotal()
        );
        boolean perPlayerLimitEnabled = perPlayerLimit > 0;
        if (!simpleClaimsEnabled && !perPlayerLimitEnabled) {
            return Decision.allowNoPopulationChecks();
        }

        String worldName = resolveWorldName(store);
        ClaimReservationKey claimReservationKey = null;
        List<PlayerReservationKey> playerReservationKeys = new ArrayList<>();
        List<ConstraintState> activeConstraints = new ArrayList<>();

        if (simpleClaimsEnabled) {
            if (spawnPosition == null || store == null) {
                warnFailClosed("SimpleClaims breeding limit check failed: spawn/store context was missing.");
                return Decision.deny("missing-spawn-context");
            }
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
                if (TameworkRuntimeSettings.simpleClaimsBreedingRequiresClaim(
                        simpleClaimsConfig.isSimpleClaimsBreedingRequiresClaim()
                )) {
                    return Decision.deny("claim-required");
                }
            } else if (resolvedClaim.status() == ClaimResolutionStatus.CLAIM_FOUND) {
                claimReservationKey = resolvedClaim.key();
                if (claimReservationKey == null) {
                    return Decision.deny("missing-claim-key");
                }
                CountResult countResult = countOwnedPopulationInClaim(
                        store,
                        worldName,
                        claimReservationKey.partyId()
                );
                if (!countResult.success()) {
                    warnFailClosed(
                            "SimpleClaims breeding limit check failed: could not count claim population ("
                                    + countResult.message()
                                    + ")."
                    );
                    return Decision.deny("simpleclaims-population-count-error");
                }
                ConstraintState claimConstraint = evaluateClaimConstraint(
                        simpleClaimsConfig,
                        resolvedClaim,
                        countResult.count(),
                        pendingClaimReservations == null
                                ? 0
                                : pendingClaimReservations.getOrDefault(claimReservationKey, 0)
                );
                if (claimConstraint != null) {
                    activeConstraints.add(claimConstraint);
                }
            }
        }

        if (perPlayerLimitEnabled) {
            boolean inheritOwner = resolveInheritOwner(config, inheritanceRoleId);
            List<UUID> ownerTargets = resolveOwnerTargets(inheritOwner, parentAOwnerId, parentBOwnerId);
            if (!ownerTargets.isEmpty()) {
                TwGlobalConfig.PerPlayerLimitScope scope = TameworkRuntimeSettings.populationPerPlayerLimitScope(
                        activeGlobalConfig.getPopulationPerPlayerLimitScope()
                );
                if (scope == TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                        && (worldName == null || worldName.isBlank())) {
                    return Decision.deny("missing-world-name");
                }
                for (UUID ownerId : ownerTargets) {
                    if (ownerId == null) {
                        continue;
                    }
                    PlayerReservationKey reservationKey = scope == TwGlobalConfig.PerPlayerLimitScope.GLOBAL
                            ? PlayerReservationKey.global(ownerId)
                            : PlayerReservationKey.perWorld(worldName, ownerId);
                    playerReservationKeys.add(reservationKey);
                    int currentCount = countOwnedPopulationForOwner(scope, store, ownerId);
                    int pendingReservations = pendingPlayerReservations == null
                            ? 0
                            : Math.max(0, pendingPlayerReservations.getOrDefault(reservationKey, 0));
                    int remainingHeadroom = perPlayerLimit - Math.max(0, currentCount) - pendingReservations;
                    activeConstraints.add(
                            new ConstraintState(
                                    ConstraintType.PLAYER,
                                    perPlayerLimit,
                                    Math.max(0, currentCount),
                                    pendingReservations,
                                    Math.max(0, remainingHeadroom)
                            )
                    );
                }
            }
        }

        if (activeConstraints.isEmpty()) {
            String reason;
            if (claimReservationKey != null) {
                reason = "claim-no-cap";
            } else if (simpleClaimsEnabled) {
                reason = "outside-claim";
            } else {
                reason = "population-cap-no-owner";
            }
            return Decision.allowWithoutCap(claimReservationKey, playerReservationKeys, reason);
        }

        ConstraintState limiting = selectLimitingConstraint(activeConstraints);
        if (limiting == null) {
            return Decision.allowWithoutCap(claimReservationKey, playerReservationKeys, "population-cap-noop");
        }
        if (limiting.remainingHeadroom() <= 0) {
            return Decision.denyAtCap(
                    limiting.type(),
                    limiting.effectiveCap(),
                    limiting.currentCount(),
                    limiting.pendingReservations(),
                    claimReservationKey,
                    playerReservationKeys
            );
        }
        return Decision.allowWithCap(
                limiting.type(),
                limiting.effectiveCap(),
                limiting.currentCount(),
                limiting.pendingReservations(),
                limiting.remainingHeadroom(),
                claimReservationKey,
                playerReservationKeys
        );
    }

    @Nonnull
    static Decision evaluateResolved(@Nonnull TwGlobalConfig globalConfig,
                                     @Nonnull ResolvedClaim resolvedClaim,
                                     int currentCount,
                                     int pendingReservations) {
        if (!TameworkRuntimeSettings.simpleClaimsEnabled(globalConfig.isSimpleClaimsEnabled())) {
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
        ConstraintState claimConstraint = evaluateClaimConstraint(
                globalConfig,
                resolvedClaim,
                currentCount,
                pendingReservations
        );
        if (claimConstraint == null) {
            return Decision.allowWithoutCap(claimKey, List.of(), "claim-no-cap");
        }
        if (claimConstraint.remainingHeadroom() <= 0) {
            return Decision.denyAtCap(
                    claimConstraint.type(),
                    claimConstraint.effectiveCap(),
                    claimConstraint.currentCount(),
                    claimConstraint.pendingReservations(),
                    claimKey,
                    List.of()
            );
        }
        return Decision.allowWithCap(
                claimConstraint.type(),
                claimConstraint.effectiveCap(),
                claimConstraint.currentCount(),
                claimConstraint.pendingReservations(),
                claimConstraint.remainingHeadroom(),
                claimKey,
                List.of()
        );
    }

    @Nonnull
    static Decision evaluatePerPlayerResolved(int perPlayerLimit,
                                              int currentCount,
                                              int pendingReservations) {
        int safeLimit = Math.max(0, perPlayerLimit);
        if (safeLimit <= 0) {
            return Decision.allowWithoutCap(null, List.of(), "player-cap-disabled");
        }
        int safeCurrent = Math.max(0, currentCount);
        int safePending = Math.max(0, pendingReservations);
        int remaining = safeLimit - safeCurrent - safePending;
        if (remaining <= 0) {
            return Decision.denyAtCap(
                    ConstraintType.PLAYER,
                    safeLimit,
                    safeCurrent,
                    safePending,
                    null,
                    List.of()
            );
        }
        return Decision.allowWithCap(
                ConstraintType.PLAYER,
                safeLimit,
                safeCurrent,
                safePending,
                remaining,
                null,
                List.of()
        );
    }

    @Nullable
    private static ConstraintState evaluateClaimConstraint(@Nonnull TwGlobalConfig globalConfig,
                                                           @Nonnull ResolvedClaim resolvedClaim,
                                                           int currentCount,
                                                           int pendingReservations) {
        if (!TameworkRuntimeSettings.simpleClaimsEnabled(globalConfig.isSimpleClaimsEnabled())) {
            return null;
        }
        if (resolvedClaim.status() != ClaimResolutionStatus.CLAIM_FOUND || resolvedClaim.key() == null) {
            return null;
        }
        int safeCurrent = Math.max(0, currentCount);
        int safePending = Math.max(0, pendingReservations);
        int claimChunks = Math.max(0, resolvedClaim.claimChunkCount());
        int chunkCap = resolveSimpleClaimsBreedingLimitPerClaimChunkCap(
                TameworkRuntimeSettings.simpleClaimsLimitPerClaimChunk(
                        globalConfig.getSimpleClaimsBreedingLimitPerClaimChunk()
                ),
                claimChunks
        );
        int totalCap = TameworkRuntimeSettings.simpleClaimsLimitPerClaimTotal(
                globalConfig.getSimpleClaimsBreedingLimitPerClaimTotal()
        );
        boolean chunkCapEnabled = chunkCap > 0;
        boolean totalCapEnabled = totalCap > 0;
        if (!chunkCapEnabled && !totalCapEnabled) {
            return null;
        }
        int effectiveCap = Integer.MAX_VALUE;
        if (chunkCapEnabled) {
            effectiveCap = Math.min(effectiveCap, chunkCap);
        }
        if (totalCapEnabled) {
            effectiveCap = Math.min(effectiveCap, totalCap);
        }
        if (effectiveCap == Integer.MAX_VALUE) {
            return null;
        }
        int remaining = effectiveCap - safeCurrent - safePending;
        return new ConstraintState(
                ConstraintType.CLAIM,
                Math.max(0, effectiveCap),
                safeCurrent,
                safePending,
                Math.max(0, remaining)
        );
    }

    private static int resolveSimpleClaimsBreedingLimitPerClaimChunkCap(int perChunk, int claimChunkCount) {
        if (perChunk <= 0) {
            return 0;
        }
        int safeChunkCount = Math.max(0, claimChunkCount);
        long multiplied = (long) perChunk * (long) safeChunkCount;
        if (multiplied > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) multiplied;
    }

    @Nullable
    private static ConstraintState selectLimitingConstraint(@Nonnull List<ConstraintState> constraints) {
        ConstraintState limiting = null;
        for (ConstraintState candidate : constraints) {
            if (candidate == null) {
                continue;
            }
            if (limiting == null || candidate.remainingHeadroom() < limiting.remainingHeadroom()) {
                limiting = candidate;
            }
        }
        return limiting;
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
    private CountResult countOwnedPopulationInClaim(@Nonnull Store<EntityStore> store,
                                                    @Nonnull String worldName,
                                                    @Nonnull UUID claimPartyId) {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (npcType == null || transformType == null || ownerType == null) {
            return new CountResult(false, 0, "required component types unavailable");
        }

        int[] count = new int[] {0};
        boolean[] failed = new boolean[] {false};
        String[] failureMessage = new String[] {null};
        Map<String, ResolvedClaim> claimLookupCache = new HashMap<>();

        store.forEachChunk(
                Query.and(npcType, transformType, ownerType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        countOwnedChunkPopulationInClaim(
                                chunk,
                                npcType,
                                transformType,
                                ownerType,
                                worldName,
                                claimPartyId,
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

    private void countOwnedChunkPopulationInClaim(@Nonnull ArchetypeChunk<EntityStore> chunk,
                                                  @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                                                  @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
                                                  @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
                                                  @Nonnull String worldName,
                                                  @Nonnull UUID claimPartyId,
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
            TameworkOwnerComponent owner = chunk.getComponent(i, ownerType);
            if (ref == null
                    || !ref.isValid()
                    || npc == null
                    || transform == null
                    || owner == null
                    || owner.getOwnerId() == null) {
                continue;
            }
            Vector3d position = transform.getPosition();
            if (position == null) {
                continue;
            }
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

    private int countOwnedPopulationForOwner(@Nonnull TwGlobalConfig.PerPlayerLimitScope scope,
                                             @Nullable Store<EntityStore> store,
                                             @Nonnull UUID ownerId) {
        return OwnerPopulationCapService.countOwnedPopulation(scope, store, ownerId);
    }

    @Nonnull
    static List<UUID> resolveOwnerTargets(boolean inheritOwner,
                                          @Nullable UUID parentAOwnerId,
                                          @Nullable UUID parentBOwnerId) {
        if (inheritOwner) {
            if (parentAOwnerId != null) {
                return List.of(parentAOwnerId);
            }
            if (parentBOwnerId != null) {
                return List.of(parentBOwnerId);
            }
            return List.of();
        }
        Set<UUID> uniqueOwners = new LinkedHashSet<>();
        if (parentAOwnerId != null) {
            uniqueOwners.add(parentAOwnerId);
        }
        if (parentBOwnerId != null) {
            uniqueOwners.add(parentBOwnerId);
        }
        return uniqueOwners.isEmpty() ? List.of() : List.copyOf(uniqueOwners);
    }

    private static boolean resolveInheritOwner(@Nullable TwBreedingConfig config,
                                               @Nullable String inheritanceRoleId) {
        if (config == null) {
            return true;
        }
        TwBreedingConfig.InheritanceSettings inheritance = config.resolveInheritance(inheritanceRoleId);
        return inheritance == null || inheritance.isInheritOwner();
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

    enum ConstraintType {
        CLAIM("claim-cap-allow", "claim-cap-reached"),
        PLAYER("player-cap-allow", "player-cap-reached");

        private final String allowReason;
        private final String denyReason;

        ConstraintType(@Nonnull String allowReason, @Nonnull String denyReason) {
            this.allowReason = allowReason;
            this.denyReason = denyReason;
        }

        @Nonnull
        String allowReason() {
            return allowReason;
        }

        @Nonnull
        String denyReason() {
            return denyReason;
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
            playerReservationKeys = playerReservationKeys == null ? List.of() : List.copyOf(playerReservationKeys);
        }

        static Decision allowNoPopulationChecks() {
            return new Decision(true, false, 0, 0, 0, Integer.MAX_VALUE, null, List.of(), "population-caps-disabled");
        }

        static Decision allowWithoutCap(@Nullable ClaimReservationKey claimKey,
                                        @Nonnull List<PlayerReservationKey> playerKeys,
                                        @Nonnull String reason) {
            return new Decision(
                    true,
                    false,
                    0,
                    0,
                    0,
                    Integer.MAX_VALUE,
                    claimKey,
                    playerKeys,
                    reason
            );
        }

        static Decision allowWithCap(@Nonnull ConstraintType constraintType,
                                     int effectiveCap,
                                     int currentCount,
                                     int pendingReservations,
                                     int remainingHeadroom,
                                     @Nullable ClaimReservationKey claimKey,
                                     @Nonnull List<PlayerReservationKey> playerKeys) {
            return new Decision(
                    true,
                    true,
                    Math.max(0, effectiveCap),
                    Math.max(0, currentCount),
                    Math.max(0, pendingReservations),
                    Math.max(0, remainingHeadroom),
                    claimKey,
                    playerKeys,
                    constraintType.allowReason()
            );
        }

        static Decision denyAtCap(@Nonnull ConstraintType constraintType,
                                  int effectiveCap,
                                  int currentCount,
                                  int pendingReservations,
                                  @Nullable ClaimReservationKey claimKey,
                                  @Nonnull List<PlayerReservationKey> playerKeys) {
            return new Decision(
                    false,
                    true,
                    Math.max(0, effectiveCap),
                    Math.max(0, currentCount),
                    Math.max(0, pendingReservations),
                    0,
                    claimKey,
                    playerKeys,
                    constraintType.denyReason()
            );
        }

        static Decision deny(@Nonnull String reason) {
            return new Decision(false, false, 0, 0, 0, 0, null, List.of(), reason);
        }
    }

    private record ConstraintState(ConstraintType type,
                                   int effectiveCap,
                                   int currentCount,
                                   int pendingReservations,
                                   int remainingHeadroom) {
    }

    private record CountResult(boolean success, int count, @Nullable String message) {
    }
}
