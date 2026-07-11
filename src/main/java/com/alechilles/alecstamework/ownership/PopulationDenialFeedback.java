package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionDecision;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.server.core.entity.entities.Player;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps authoritative owner/claim decisions to throttled player-facing feedback. */
public final class PopulationDenialFeedback {
    private PopulationDenialFeedback() {
    }

    public static boolean sendClaimCap(@Nullable Player player,
                                       @Nullable CompanionPopulationPreparationResult result) {
        ClaimAdmissionDecision claim = result == null ? null : result.claimDecision();
        if (player == null || claim == null || !"claim-cap-reached".equals(claim.reason())) {
            return false;
        }
        long current = Math.max(
                0L,
                claim.committedPopulation() - claim.creditedDepartures() + claim.pendingPopulation()
        );
        OwnerMessageUtil.sendClaimPopulationCapReached(
                player,
                saturatingInt(current),
                saturatingInt(claim.effectiveCapacity())
        );
        return true;
    }

    public static void sendOwnerOrUnavailable(@Nullable Player player,
                                              @Nonnull String reason,
                                              @Nullable OwnerPopulationDecision decision) {
        if (player == null) {
            return;
        }
        if (decision != null && decision.limit() > 0 && "owner-cap-reached".equals(reason)) {
            OwnerMessageUtil.sendPopulationCapReached(
                    player,
                    saturatingInt(decision.committedCount() + decision.pendingCount()),
                    decision.limit(),
                    configuredScope()
            );
            return;
        }
        OwnerMessageUtil.sendPopulationUnavailable(player, reason);
    }

    private static int saturatingInt(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private static TwGlobalConfig.PerPlayerLimitScope configuredScope() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        TwGlobalConfig.PerPlayerLimitScope configured = config == null
                ? TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                : config.getPopulationPerPlayerLimitScope();
        return TameworkRuntimeSettings.populationPerPlayerLimitScope(configured);
    }
}
