package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProviderSelector;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRequest;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compatibility facade for legacy claim-policy DTOs and pure cap calculations.
 *
 * <p>Breeding runtime admission is owned by the shared population coordinators. The live lookup
 * delegate remains only for the older tame policy adapter until that adapter is retired.</p>
 */
final class BreedingClaimLimitPolicyService {
    private final LegacyClaimPopulationLookupService lookupService;

    BreedingClaimLimitPolicyService(@Nullable ClaimIntegrationBridge claimBridge) {
        OwnerPopulationRuntime populationRuntime = resolvePopulationRuntime();
        ClaimIntegrationBridge resolved = claimBridge == null ? resolveConfiguredClaimBridge() : claimBridge;
        this.lookupService = new LegacyClaimPopulationLookupService(
                resolved,
                populationRuntime == null ? null : populationRuntime.claimOccupancyIndex()
        );
    }

    @Nonnull
    static ClaimIntegrationBridge resolveConfiguredClaimBridge() {
        TwGlobalConfig config = TwGlobalConfig.resolveSimpleClaimsSettingsConfig();
        if (config == null) {
            config = TwGlobalConfig.resolveActive();
        }
        if (config == null) {
            config = TwGlobalConfig.defaultConfig();
        }
        TameworkRuntimeSettings runtimeSettings = TameworkRuntimeSettings.currentOrNull();
        ClaimProviderRequest providerRequest = runtimeSettings == null
                ? config.getSimpleClaimsProviderRequest()
                : runtimeSettings.simpleClaimsProviderRequest();
        OwnerPopulationRuntime populationRuntime = resolvePopulationRuntime();
        if (populationRuntime != null) {
            ClaimPolicyContext context = populationRuntime.claimProviderRegistry().resolveRequest(
                    providerRequest,
                    runtimeSettings == null ? 0L : runtimeSettings.revision()
            );
            return context.bridge() != null
                    ? context.bridge()
                    : ClaimIntegrationProviderSelector.unavailable(
                            context.providerId(), context.reason()
                    );
        }
        return unavailableWithoutPopulationRuntime(providerRequest);
    }

    /** Never performs permissive provider probing outside the lifecycle-aware owner runtime. */
    @Nonnull
    static ClaimIntegrationBridge unavailableWithoutPopulationRuntime(@Nullable ClaimProviderRequest request) {
        if (request == null) {
            return ClaimIntegrationProviderSelector.unavailable(
                    "invalid",
                    "Owner population runtime is unavailable and no claim provider request was supplied."
            );
        }
        if (!request.valid()) {
            return ClaimIntegrationProviderSelector.unavailable(
                    "invalid",
                    request.invalidDiagnostic("SimpleClaims.Provider")
            );
        }
        ClaimIntegrationProvider provider = request.provider();
        if (provider == ClaimIntegrationProvider.OFF) {
            return ClaimIntegrationProviderSelector.unavailable("off", "Claim integration is off.");
        }
        String providerId = switch (provider) {
            case AUTO -> "auto";
            case SIMPLE_CLAIMS -> "simpleclaims";
            case QUESTLINES_CLAIMS -> "questlines-claims";
            case OFF -> "off";
        };
        return ClaimIntegrationProviderSelector.unavailable(
                providerId,
                "Owner population runtime is unavailable; claim provider '"
                        + request.displayValue()
                        + "' cannot be resolved safely."
        );
    }

    @Nullable
    private static OwnerPopulationRuntime resolvePopulationRuntime() {
        try {
            Tamework plugin = Tamework.getInstance();
            return plugin == null ? null : plugin.getOwnerPopulationRuntime();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    @Nonnull
    ResolvedClaim resolveClaim(@Nullable String worldName, @Nullable Vector3d position) {
        return lookupService.resolveClaim(worldName, position);
    }

    @Nonnull
    CountResult countOwnedPopulationInClaim(@Nonnull Store<EntityStore> store,
                                            @Nonnull String worldName,
                                            @Nonnull ClaimReservationKey targetClaim) {
        return lookupService.countOwnedPopulationInClaim(store, worldName, targetClaim);
    }

    @Nonnull
    CountResult countOwnedPopulationInClaim(@Nonnull String worldName,
                                            @Nonnull ClaimReservationKey targetClaim) {
        return lookupService.countOwnedPopulationInClaim(worldName, targetClaim);
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

    @Nonnull
    static Decision evaluatePerPlayerResolved(int perPlayerLimit,
                                              int currentCount,
                                              int pendingReservations) {
        int limit = Math.max(0, perPlayerLimit);
        if (limit == 0) {
            return Decision.allowWithoutCap(null, List.of(), "player-cap-disabled");
        }
        int current = Math.max(0, currentCount);
        int pending = Math.max(0, pendingReservations);
        int remaining = limit - current - pending;
        return remaining <= 0
                ? Decision.denyAtCap(ConstraintType.PLAYER, limit, current, pending, null, List.of())
                : Decision.allowWithCap(
                ConstraintType.PLAYER, limit, current, pending, remaining, null, List.of()
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

    /** A child with owner inheritance disabled is unowned and consumes no owner reservation. */
    @Nonnull
    static List<UUID> resolveOwnerTargets(boolean inheritOwner,
                                          @Nullable UUID parentAOwnerId,
                                          @Nullable UUID parentBOwnerId) {
        if (!inheritOwner) {
            return List.of();
        }
        if (parentAOwnerId != null) {
            return List.of(parentAOwnerId);
        }
        return parentBOwnerId == null ? List.of() : List.of(parentBOwnerId);
    }

    private static boolean claimPolicyEnabled(@Nonnull TwGlobalConfig config) {
        return TameworkRuntimeSettings.simpleClaimsEnabled(config.isSimpleClaimsEnabled())
                && TameworkRuntimeSettings.simpleClaimsProvider(config.getSimpleClaimsProvider())
                != ClaimIntegrationProvider.OFF;
    }

    private static int multiplyCap(int perChunk, int claimChunkCount) {
        if (perChunk <= 0) {
            return 0;
        }
        long result = (long) perChunk * Math.max(0, claimChunkCount);
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
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
    }

    record ClaimReservationKey(String providerId,
                               String worldName,
                               String ownerType,
                               UUID ownerId,
                               @Nullable String claimId) {
        ClaimReservationKey(String worldName, UUID partyId) {
            this("simpleclaims", worldName, "PARTY", partyId,
                    partyId == null ? null : partyId.toString());
        }

        @Nonnull
        static ClaimReservationKey fromPopulationKey(@Nonnull ClaimPopulationKey key) {
            return new ClaimReservationKey(
                    key.providerId(), key.worldName(), key.ownerType(), key.ownerId(), key.claimId()
            );
        }

        @Nonnull
        static ClaimReservationKey simpleClaims(@Nonnull String worldName, @Nonnull UUID partyId) {
            return fromPopulationKey(ClaimPopulationKey.simpleClaims(worldName, partyId));
        }

        UUID partyId() {
            return ownerId;
        }
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
}
