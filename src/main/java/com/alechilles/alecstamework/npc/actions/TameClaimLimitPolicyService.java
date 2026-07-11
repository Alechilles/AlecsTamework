package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimWarningThrottle;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Evaluates SimpleClaims population caps when a wild NPC is tamed into player ownership.
 */
public final class TameClaimLimitPolicyService {
    private static final String WARNING_CONTEXT = "tame-admission";

    private final ClaimIntegrationBridge injectedClaimBridge;
    private final ClaimWarningThrottle warningThrottle;

    public TameClaimLimitPolicyService() {
        this(null);
    }

    TameClaimLimitPolicyService(@Nullable ClaimIntegrationBridge claimBridge) {
        this(claimBridge, new ClaimWarningThrottle());
    }

    TameClaimLimitPolicyService(@Nullable ClaimIntegrationBridge claimBridge,
                                @Nonnull ClaimWarningThrottle warningThrottle) {
        this.injectedClaimBridge = claimBridge;
        this.warningThrottle = Objects.requireNonNull(warningThrottle, "warningThrottle");
    }

    @Nonnull
    public BreedingClaimLimitPolicyService.Decision evaluate(@Nullable Store<EntityStore> store,
                                                             @Nullable Vector3d tamePosition) {
        TwGlobalConfig simpleClaimsConfig = resolveSimpleClaimsConfig();
        if (!TameworkRuntimeSettings.simpleClaimsEnabled(simpleClaimsConfig.isSimpleClaimsEnabled())
                || TameworkRuntimeSettings.simpleClaimsProvider(simpleClaimsConfig.getSimpleClaimsProvider())
                == ClaimIntegrationProvider.OFF
                || !hasSimpleClaimsClaimCap(simpleClaimsConfig)) {
            return BreedingClaimLimitPolicyService.Decision.allowNoPopulationChecks();
        }
        if (store == null || tamePosition == null) {
            warnFailClosed(
                    "missing-context",
                    configuredProviderId(simpleClaimsConfig),
                    WARNING_CONTEXT,
                    "SimpleClaims tame limit check failed: target/store context was missing."
            );
            return BreedingClaimLimitPolicyService.Decision.deny("missing-tame-context");
        }
        String worldName = resolveWorldName(store);
        if (worldName == null || worldName.isBlank()) {
            warnFailClosed(
                    "missing-world",
                    configuredProviderId(simpleClaimsConfig),
                    WARNING_CONTEXT,
                    "SimpleClaims tame limit check failed: world name was missing."
            );
            return BreedingClaimLimitPolicyService.Decision.deny("missing-world-name");
        }
        ClaimIntegrationBridge claimBridge = injectedClaimBridge == null
                ? BreedingClaimLimitPolicyService.resolveConfiguredClaimBridge()
                : injectedClaimBridge;
        BreedingClaimLimitPolicyService claimPolicyService =
                new BreedingClaimLimitPolicyService(claimBridge);
        if (!claimBridge.isAvailable()) {
            warnFailClosed(
                    "provider-unavailable",
                    claimBridge.providerId(),
                    worldName,
                    "Claim integration tame limit check failed: dependency unavailable ("
                            + claimBridge.getUnavailableReason()
                            + ")."
            );
            return BreedingClaimLimitPolicyService.Decision.deny("claim-provider-unavailable");
        }
        BreedingClaimLimitPolicyService.ResolvedClaim resolvedClaim =
                claimPolicyService.resolveClaim(worldName, tamePosition);
        if (resolvedClaim.status() == BreedingClaimLimitPolicyService.ClaimResolutionStatus.NO_CLAIM) {
            return BreedingClaimLimitPolicyService.Decision.allowWithoutCap(null, List.of(), "outside-claim");
        }
        if (resolvedClaim.status() == BreedingClaimLimitPolicyService.ClaimResolutionStatus.UNAVAILABLE
                || resolvedClaim.status() == BreedingClaimLimitPolicyService.ClaimResolutionStatus.ERROR
                || resolvedClaim.key() == null) {
            warnFailClosed(
                    "lookup-error",
                    claimBridge.providerId(),
                    worldName,
                    "SimpleClaims tame limit check failed: could not resolve claim context."
            );
            return BreedingClaimLimitPolicyService.Decision.deny("simpleclaims-lookup-error");
        }
        BreedingClaimLimitPolicyService.CountResult countResult =
                claimPolicyService.countOwnedPopulationInClaim(worldName, resolvedClaim.key());
        if (!countResult.success()) {
            warnFailClosed(
                    "population-count-error",
                    claimBridge.providerId(),
                    worldName,
                    "SimpleClaims tame limit check failed: could not count claim population ("
                            + countResult.message()
                            + ")."
            );
            return BreedingClaimLimitPolicyService.Decision.deny("simpleclaims-population-count-error");
        }
        return evaluateResolved(simpleClaimsConfig, resolvedClaim, countResult.count());
    }

    @Nonnull
    public TameLimitDecision evaluateForTame(@Nullable Store<EntityStore> store,
                                             @Nullable Vector3d tamePosition) {
        return TameLimitDecision.from(evaluate(store, tamePosition));
    }

    @Nonnull
    static BreedingClaimLimitPolicyService.Decision evaluateResolved(
            @Nonnull TwGlobalConfig globalConfig,
            @Nonnull BreedingClaimLimitPolicyService.ResolvedClaim resolvedClaim,
            int currentCount) {
        if (!TameworkRuntimeSettings.simpleClaimsEnabled(globalConfig.isSimpleClaimsEnabled())
                || TameworkRuntimeSettings.simpleClaimsProvider(globalConfig.getSimpleClaimsProvider())
                == ClaimIntegrationProvider.OFF
                || !hasSimpleClaimsClaimCap(globalConfig)) {
            return BreedingClaimLimitPolicyService.Decision.allowNoPopulationChecks();
        }
        if (resolvedClaim.status() == BreedingClaimLimitPolicyService.ClaimResolutionStatus.NO_CLAIM) {
            return BreedingClaimLimitPolicyService.Decision.allowWithoutCap(null, List.of(), "outside-claim");
        }
        if (resolvedClaim.status() == BreedingClaimLimitPolicyService.ClaimResolutionStatus.UNAVAILABLE
                || resolvedClaim.status() == BreedingClaimLimitPolicyService.ClaimResolutionStatus.ERROR
                || resolvedClaim.key() == null) {
            return BreedingClaimLimitPolicyService.Decision.deny("simpleclaims-lookup-error");
        }
        BreedingClaimLimitPolicyService.ConstraintState claimConstraint =
                BreedingClaimLimitPolicyService.evaluateClaimConstraint(globalConfig, resolvedClaim, currentCount, 0);
        if (claimConstraint == null) {
            return BreedingClaimLimitPolicyService.Decision.allowWithoutCap(
                    resolvedClaim.key(),
                    List.of(),
                    "claim-no-cap"
            );
        }
        if (claimConstraint.remainingHeadroom() <= 0) {
            return BreedingClaimLimitPolicyService.Decision.denyAtCap(
                    BreedingClaimLimitPolicyService.ConstraintType.CLAIM,
                    claimConstraint.effectiveCap(),
                    claimConstraint.currentCount(),
                    claimConstraint.pendingReservations(),
                    resolvedClaim.key(),
                    List.of()
            );
        }
        return BreedingClaimLimitPolicyService.Decision.allowWithCap(
                BreedingClaimLimitPolicyService.ConstraintType.CLAIM,
                claimConstraint.effectiveCap(),
                claimConstraint.currentCount(),
                claimConstraint.pendingReservations(),
                claimConstraint.remainingHeadroom(),
                resolvedClaim.key(),
                List.of()
        );
    }

    private static TwGlobalConfig resolveSimpleClaimsConfig() {
        TwGlobalConfig config = TwGlobalConfig.resolveSimpleClaimsSettingsConfig();
        if (config == null) {
            config = TwGlobalConfig.resolveActive();
        }
        return config == null ? TwGlobalConfig.defaultConfig() : config;
    }

    private static boolean hasSimpleClaimsClaimCap(@Nonnull TwGlobalConfig globalConfig) {
        return TameworkRuntimeSettings.simpleClaimsLimitPerClaimChunk(
                globalConfig.getSimpleClaimsBreedingLimitPerClaimChunk()
        ) > 0
                || TameworkRuntimeSettings.simpleClaimsLimitPerClaimTotal(
                globalConfig.getSimpleClaimsBreedingLimitPerClaimTotal()
        ) > 0;
    }

    @Nonnull
    private static String configuredProviderId(@Nonnull TwGlobalConfig globalConfig) {
        return TameworkRuntimeSettings.simpleClaimsProvider(globalConfig.getSimpleClaimsProvider())
                .configValue();
    }

    @Nullable
    private static String resolveWorldName(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        return world != null ? world.getName() : null;
    }

    private void warnFailClosed(@Nonnull String category,
                                @Nullable String providerId,
                                @Nullable String context,
                                @Nullable String warning) {
        if (warning == null || warning.isBlank()) {
            return;
        }
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null) {
            return;
        }
        if (!warningThrottle.tryAcquire(category, providerId, context)) {
            return;
        }
        plugin.getLogger().at(Level.WARNING).log(warning);
    }

    /** Public tame-limit decision view for ownership-package acquisition gates. */
    public record TameLimitDecision(boolean allowed,
                                    @Nullable String reason,
                                    int currentCount,
                                    int effectiveCap) {
        @Nonnull
        static TameLimitDecision from(@Nonnull BreedingClaimLimitPolicyService.Decision decision) {
            return new TameLimitDecision(
                    decision.allowed(),
                    decision.reason(),
                    decision.currentCount(),
                    decision.effectiveCap()
            );
        }

        public boolean claimCapReached() {
            return "claim-cap-reached".equals(reason);
        }
    }
}
