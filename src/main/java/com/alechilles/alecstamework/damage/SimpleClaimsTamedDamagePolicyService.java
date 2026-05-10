package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Evaluates optional claim-based damage restrictions for tamed NPC targets.
 */
final class SimpleClaimsTamedDamagePolicyService {
    private static final long WARNING_THROTTLE_MS = 60_000L;

    private final SimpleClaimsBreedingBridge simpleClaimsBridge;
    private long nextWarningAtMs;

    SimpleClaimsTamedDamagePolicyService() {
        this(SimpleClaimsBreedingBridge.initialize());
    }

    SimpleClaimsTamedDamagePolicyService(@Nullable SimpleClaimsBreedingBridge simpleClaimsBridge) {
        this.simpleClaimsBridge = simpleClaimsBridge == null
                ? SimpleClaimsBreedingBridge.initialize()
                : simpleClaimsBridge;
    }

    @Nonnull
    Decision evaluate(@Nullable Ref<EntityStore> targetRef,
                      @Nullable Store<EntityStore> store,
                      @Nullable Vector3d targetPosition,
                      @Nullable UUID attackerPlayerUuid,
                      @Nullable TwGlobalConfig globalConfig) {
        if (globalConfig == null) {
            return Decision.allowSkipped("damage-protection-disabled");
        }
        boolean simpleClaimsEnabled = TameworkRuntimeSettings.simpleClaimsEnabled(globalConfig.isSimpleClaimsEnabled());
        boolean protectTamedFromNonMembers = TameworkRuntimeSettings.simpleClaimsProtectTamedFromNonMembers(
                globalConfig.isSimpleClaimsDamageProtectTamedFromNonMembers()
        );
        if (!simpleClaimsEnabled || !protectTamedFromNonMembers) {
            return Decision.allowSkipped("damage-protection-disabled");
        }
        if (attackerPlayerUuid == null) {
            return Decision.allowSkipped("attacker-unattributed");
        }
        if (targetRef == null || !targetRef.isValid() || store == null) {
            return Decision.allowSkipped("target-context-missing");
        }
        if (!TamedStateResolver.isTamed(targetRef, store)) {
            return Decision.allowSkipped("target-not-tamed");
        }
        String worldName = resolveWorldName(store);
        if (worldName == null || worldName.isBlank() || targetPosition == null) {
            warnFailOpen("SimpleClaims tamed damage check failed: target/world context was missing.");
            return Decision.allowFailOpen("lookup-context-missing");
        }
        SimpleClaimsBreedingBridge.DamageAccessResult accessResult = simpleClaimsBridge.evaluateDamageAccess(
                worldName,
                targetPosition,
                attackerPlayerUuid,
                globalConfig.getSimpleClaimsDamageAllowDamagePermissionKey()
        );
        Decision resolved = evaluateResolved(accessResult);
        if (resolved.status() == DecisionStatus.ALLOW_FAIL_OPEN) {
            warnFailOpen(
                    "SimpleClaims tamed damage check failed-open: status="
                            + (accessResult != null ? accessResult.status() : "<null>")
                            + ", message="
                            + (accessResult != null ? accessResult.message() : "missing-result")
                            + "."
            );
        }
        return resolved;
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
        World world = store.getExternalData().getWorld();
        return world != null ? world.getName() : null;
    }

    private void warnFailOpen(@Nullable String warning) {
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
