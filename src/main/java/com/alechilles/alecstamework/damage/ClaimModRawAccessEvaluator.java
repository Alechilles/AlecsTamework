package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimModDamageProvider;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * The claim-only public API answer for a claim mod read through {@link ClaimModDamageProvider}
 * (QuestLines Claims), with the same statuses as {@link SimpleClaimsRawAccessEvaluator}. Such
 * claims have no SimpleClaims party, so the party id is always null.
 */
final class ClaimModRawAccessEvaluator {
    private final ClaimModDamageProvider provider;

    ClaimModRawAccessEvaluator(@Nonnull ClaimModDamageProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Nonnull
    SimpleClaimsRawAccessDecision evaluate(@Nullable String worldName,
                                           @Nullable Vector3d targetPosition,
                                           @Nullable UUID attackerPlayerUuid) {
        if (worldName == null || worldName.isBlank() || !isFinite(targetPosition)) {
            return unavailable("live-claim-context-missing");
        }
        ClaimLookupResult lookup;
        try {
            lookup = provider.lookupClaim(worldName, targetPosition);
        } catch (RuntimeException | LinkageError failed) {
            return failOpen(message(failed));
        }
        if (lookup == null) {
            return failOpen("claim-lookup-returned-null");
        }
        return switch (lookup.status()) {
            case NO_CLAIM -> decision(true, true, SimpleClaimsRawAccessDecision.Status.ALLOWED, "outside-claim");
            case UNAVAILABLE -> unavailable(firstNonBlank(lookup.message(), "claim-mod-unavailable"));
            case ERROR -> failOpen(firstNonBlank(lookup.message(), "claim-lookup-error"));
            case CLAIM_FOUND -> attackerPlayerUuid == null
                    ? failOpen("attacker-unattributed")
                    : evaluateDamage(worldName, targetPosition, attackerPlayerUuid);
        };
    }

    @Nonnull
    private SimpleClaimsRawAccessDecision evaluateDamage(@Nonnull String worldName,
                                                         @Nonnull Vector3d targetPosition,
                                                         @Nonnull UUID attackerPlayerUuid) {
        ClaimModDamageProvider.DamageResult result;
        try {
            result = provider.evaluateTamedDamage(worldName, targetPosition, attackerPlayerUuid);
        } catch (RuntimeException | LinkageError failed) {
            return failOpen(message(failed));
        }
        if (result == null) {
            return failOpen("claim-damage-result-missing");
        }
        String reason = firstNonBlank(result.message(), result.status().name().toLowerCase(Locale.ROOT));
        return switch (result.status()) {
            case ALLOWED -> decision(true, true, SimpleClaimsRawAccessDecision.Status.ALLOWED, reason);
            case DENIED -> decision(true, false, SimpleClaimsRawAccessDecision.Status.DENIED, reason);
            case ERROR -> failOpen(reason);
            case UNAVAILABLE -> unavailable(reason);
        };
    }

    @Nonnull
    private static SimpleClaimsRawAccessDecision failOpen(@Nonnull String reason) {
        return decision(true, true, SimpleClaimsRawAccessDecision.Status.ALLOW_FAIL_OPEN, reason);
    }

    @Nonnull
    private static SimpleClaimsRawAccessDecision unavailable(@Nonnull String reason) {
        return decision(false, true, SimpleClaimsRawAccessDecision.Status.UNAVAILABLE, reason);
    }

    @Nonnull
    private static SimpleClaimsRawAccessDecision decision(boolean available,
                                                          boolean allowed,
                                                          @Nonnull SimpleClaimsRawAccessDecision.Status status,
                                                          @Nonnull String reason) {
        return new SimpleClaimsRawAccessDecision(available, allowed, status, reason, null);
    }

    private static boolean isFinite(@Nullable Vector3d position) {
        return position != null
                && Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    @Nonnull
    private static String firstNonBlank(@Nullable String preferred, @Nonnull String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    @Nonnull
    private static String message(@Nonnull Throwable throwable) {
        String detail = throwable.getMessage();
        return detail == null || detail.isBlank() ? throwable.getClass().getSimpleName() : detail;
    }
}
