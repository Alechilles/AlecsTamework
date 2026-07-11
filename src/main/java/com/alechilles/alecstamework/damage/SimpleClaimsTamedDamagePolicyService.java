package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compatibility wrapper for callers that used the original claim-only policy service.
 */
final class SimpleClaimsTamedDamagePolicyService {
    private final SimpleClaimsTamedDamagePolicy policy;

    SimpleClaimsTamedDamagePolicyService() {
        this(SimpleClaimsBreedingBridge.initialize());
    }

    SimpleClaimsTamedDamagePolicyService(@Nullable SimpleClaimsBreedingBridge simpleClaimsBridge) {
        SimpleClaimsBreedingBridge bridge = simpleClaimsBridge == null
                ? SimpleClaimsBreedingBridge.initialize()
                : simpleClaimsBridge;
        this.policy = new SimpleClaimsTamedDamagePolicy(bridge);
    }

    @Nonnull
    Decision evaluate(@Nullable Ref<EntityStore> targetRef,
                      @Nullable Store<EntityStore> store,
                      @Nullable Vector3d targetPosition,
                      @Nullable UUID attackerPlayerUuid,
                      @Nullable TwGlobalConfig globalConfig) {
        TamedDamageDecision decision = policy.evaluate(
                TamedDamageOwnerPolicy.unowned(),
                targetRef,
                store,
                resolveWorldName(store),
                targetPosition,
                attackerPlayerUuid,
                globalConfig
        );
        return mapDecision(decision);
    }

    @Nonnull
    static Decision evaluateResolved(@Nullable SimpleClaimsBreedingBridge.DamageAccessResult accessResult) {
        if (accessResult == null) {
            return Decision.allowFailOpen("missing-access-result");
        }
        return switch (accessResult.status()) {
            case ALLOWED -> Decision.allowEnforced("allowed");
            case DENIED -> Decision.deny("claim-protection-denied");
            case LOOKUP_ERROR -> Decision.allowFailOpen("lookup-error");
            case UNAVAILABLE -> Decision.allowFailOpen("bridge-unavailable");
        };
    }

    @Nullable
    private static String resolveWorldName(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            return null;
        }
        return store.getExternalData().getWorld().getName();
    }

    @Nonnull
    private static Decision mapDecision(@Nonnull TamedDamageDecision decision) {
        return switch (decision.status()) {
            case ALLOW_SKIPPED, UNAVAILABLE -> Decision.allowSkipped(decision.reason());
            case ALLOW_ENFORCED -> Decision.allowEnforced(decision.reason());
            case DENY_OWNER, DENY_CLAIM -> Decision.deny(decision.reason());
            case ALLOW_FAIL_OPEN -> Decision.allowFailOpen(decision.reason());
        };
    }

    enum DecisionStatus {
        ALLOW_SKIPPED,
        ALLOW_ENFORCED,
        DENY,
        ALLOW_FAIL_OPEN
    }

    record Decision(boolean allowed, DecisionStatus status, String reason) {
        static Decision allowSkipped(@Nonnull String reason) {
            return new Decision(true, DecisionStatus.ALLOW_SKIPPED, reason);
        }

        static Decision allowEnforced(@Nonnull String reason) {
            return new Decision(true, DecisionStatus.ALLOW_ENFORCED, reason);
        }

        static Decision deny(@Nonnull String reason) {
            return new Decision(false, DecisionStatus.DENY, reason);
        }

        static Decision allowFailOpen(@Nonnull String reason) {
            return new Decision(true, DecisionStatus.ALLOW_FAIL_OPEN, reason);
        }
    }
}
