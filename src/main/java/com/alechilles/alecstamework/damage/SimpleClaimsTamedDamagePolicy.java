package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Shared owner-first and SimpleClaims-native tamed-target damage policy.
 *
 * <p>Both the runtime damage filter and public API call this class. It resolves eligibility from
 * the live target, treats the configurable bypass as a server permission, retains the historical
 * raw-party grant for one compatibility release, and fails open only when optional integration
 * access cannot be evaluated.</p>
 */
public final class SimpleClaimsTamedDamagePolicy implements AutoCloseable {
    private static final String WARN_FAIL_OPEN = "simpleclaims-damage-fail-open";
    private static final String WARN_LEGACY_BYPASS = "simpleclaims-legacy-damage-bypass";
    private static final String WARN_PERMISSION = "simpleclaims-damage-server-permission";

    private final TamedDamageTargetEligibilityResolver eligibilityResolver;
    private final TamedDamagePolicyAdapter decisionAdapter;
    private final SimpleClaimsDamageCapabilityResolver capabilityResolver;
    private final SimpleClaimsRawAccessEvaluator rawAccessEvaluator;
    private final DamageServerPermissionBypass serverPermissionBypass;
    private final DamagePolicyWarningSink warningSink;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Creates a production policy that resolves the live SimpleClaims generation per decision. */
    public SimpleClaimsTamedDamagePolicy() {
        this(
                new TamedDamageTargetEligibilityResolver(),
                new SimpleClaimsDamageCapabilityRegistry(),
                new HytaleDamageServerPermissionBypass(),
                new ThrottledDamagePolicyWarningSink()
        );
    }

    SimpleClaimsTamedDamagePolicy(@Nonnull SimpleClaimsBreedingBridge bridge) {
        this(
                new TamedDamageTargetEligibilityResolver(),
                fixedResolver(
                        bridge::evaluateDamageAccess,
                        new ReflectiveLegacySimpleClaimsPartyPermissionBypass(bridge)
                ),
                new HytaleDamageServerPermissionBypass(),
                new ThrottledDamagePolicyWarningSink()
        );
    }

    SimpleClaimsTamedDamagePolicy(
            @Nonnull TamedDamageTargetEligibilityResolver eligibilityResolver,
            @Nonnull NativeSimpleClaimsDamageAccess nativeAccess,
            @Nonnull DamageServerPermissionBypass serverPermissionBypass,
            @Nonnull LegacySimpleClaimsPartyPermissionBypass legacyPartyBypass,
            @Nonnull DamagePolicyWarningSink warningSink) {
        this(
                eligibilityResolver,
                fixedResolver(nativeAccess, legacyPartyBypass),
                serverPermissionBypass,
                warningSink
        );
    }

    SimpleClaimsTamedDamagePolicy(
            @Nonnull TamedDamageTargetEligibilityResolver eligibilityResolver,
            @Nonnull SimpleClaimsDamageCapabilityResolver capabilityResolver,
            @Nonnull DamageServerPermissionBypass serverPermissionBypass,
            @Nonnull DamagePolicyWarningSink warningSink) {
        this.eligibilityResolver = eligibilityResolver;
        this.decisionAdapter = new TamedDamagePolicyAdapter();
        this.capabilityResolver = capabilityResolver;
        this.rawAccessEvaluator = new SimpleClaimsRawAccessEvaluator(capabilityResolver);
        this.serverPermissionBypass = serverPermissionBypass;
        this.warningSink = warningSink;
    }

    /**
     * Evaluates owner protection and, when active and eligible, native SimpleClaims damage access.
     */
    @Nonnull
    public TamedDamageDecision evaluate(
            @Nullable TamedDamageOwnerPolicy ownerPolicy,
            @Nullable Ref<EntityStore> targetRef,
            @Nullable Store<EntityStore> store,
            @Nullable String worldName,
            @Nullable Vector3d targetPosition,
            @Nullable UUID attackerPlayerUuid,
            @Nullable TwGlobalConfig globalConfig) {
        boolean integrationEnabled = globalConfig != null
                && TameworkRuntimeSettings.simpleClaimsEnabled(globalConfig.isSimpleClaimsEnabled());
        boolean protectionEnabled = globalConfig != null
                && TameworkRuntimeSettings.simpleClaimsProtectTamedFromNonMembers(
                globalConfig.isSimpleClaimsDamageProtectTamedFromNonMembers()
        );

        Optional<TamedDamageDecision> earlyDecision = decisionAdapter.evaluatePreconditions(
                ownerPolicy,
                attackerPlayerUuid,
                integrationEnabled,
                protectionEnabled,
                TamedDamageTargetEligibilityResolver.Status.ELIGIBLE
        );
        if (earlyDecision.isPresent()) {
            return earlyDecision.get();
        }

        TamedDamageTargetEligibilityResolver.Status eligibility = eligibilityResolver.resolve(targetRef, store);
        return evaluateResolvedEligibility(
                ownerPolicy,
                eligibility,
                worldName,
                targetPosition,
                attackerPlayerUuid,
                globalConfig
        );
    }

    @Nonnull
    TamedDamageDecision evaluateResolvedEligibility(
            @Nullable TamedDamageOwnerPolicy ownerPolicy,
            @Nonnull TamedDamageTargetEligibilityResolver.Status eligibility,
            @Nullable String worldName,
            @Nullable Vector3d targetPosition,
            @Nullable UUID attackerPlayerUuid,
            @Nullable TwGlobalConfig globalConfig) {
        boolean integrationEnabled = globalConfig != null
                && TameworkRuntimeSettings.simpleClaimsEnabled(globalConfig.isSimpleClaimsEnabled());
        boolean protectionEnabled = globalConfig != null
                && TameworkRuntimeSettings.simpleClaimsProtectTamedFromNonMembers(
                globalConfig.isSimpleClaimsDamageProtectTamedFromNonMembers()
        );
        Optional<TamedDamageDecision> eligibilityDecision = decisionAdapter.evaluatePreconditions(
                ownerPolicy,
                attackerPlayerUuid,
                integrationEnabled,
                protectionEnabled,
                eligibility
        );
        if (eligibilityDecision.isPresent()) {
            return eligibilityDecision.get();
        }
        if (worldName == null || worldName.isBlank() || !isFinite(targetPosition)) {
            return failOpen(false, null, "lookup-context-missing", "Target/world context was missing.");
        }

        String permissionKey = normalizePermissionKey(
                globalConfig != null ? globalConfig.getSimpleClaimsDamageAllowDamagePermissionKey() : null
        );
        return evaluateLiveClaimPolicy(
                worldName,
                targetPosition,
                attackerPlayerUuid,
                permissionKey
        );
    }

    /**
     * Evaluates the legacy claim-only public API without invoking population topology.
     * Damage protection's enable toggle intentionally remains outside this legacy contract.
     */
    @Nonnull
    public SimpleClaimsRawAccessDecision evaluateRawClaimAccess(
            @Nullable String worldName,
            @Nullable Vector3d targetPosition,
            @Nullable UUID attackerPlayerUuid,
            @Nullable TwGlobalConfig globalConfig) {
        return rawAccessEvaluator.evaluate(worldName, targetPosition, attackerPlayerUuid, globalConfig);
    }

    @Nonnull
    private TamedDamageDecision evaluateLiveClaimPolicy(
            @Nonnull String worldName,
            @Nonnull Vector3d targetPosition,
            @Nonnull UUID attackerPlayerUuid,
            @Nullable String permissionKey) {
        if (permissionKey != null && hasServerPermission(attackerPlayerUuid, permissionKey)) {
            return TamedDamageDecision.allowEnforced("server-permission-bypass", null, null);
        }

        SimpleClaimsDamageCapabilityResolver.Resolution resolution = resolveCapability();
        SimpleClaimsDamageGeneration capability = resolution.capability();
        if (resolution.state() != SimpleClaimsPluginState.READY
                || capability == null) {
            return failOpen(
                    false,
                    null,
                    "bridge-unavailable",
                    firstNonBlank(resolution.reason(), "SimpleClaims damage capability is unavailable.")
            );
        }

        LegacySimpleClaimsPartyPermissionBypass.Result legacyResult = permissionKey != null
                ? evaluateLegacyBypass(
                        capability.legacyPartyBypass(),
                        worldName,
                        targetPosition,
                        attackerPlayerUuid,
                        permissionKey
                )
                : LegacySimpleClaimsPartyPermissionBypass.Result.notGranted();
        if (legacyResult.status() == LegacySimpleClaimsPartyPermissionBypass.Status.GRANTED) {
            warningSink.warn(
                    WARN_LEGACY_BYPASS,
                    "Tamework granted SimpleClaims tamed damage through the deprecated raw-party "
                            + "AllowDamagePermissionKey compatibility path. Migrate this grant to the Hytale "
                            + "server permission before the next major release."
            );
            return TamedDamageDecision.allowEnforced(
                    "legacy-party-permission-bypass",
                    legacyResult.claimPartyId(),
                    legacyResult.message()
            );
        }

        try {
            TamedDamageDecision decision = decisionAdapter.mapNative(capability.nativeAccess().evaluate(
                    worldName,
                    targetPosition,
                    attackerPlayerUuid,
                    permissionKey
            ));
            if (decision.status() == TamedDamageDecision.Status.ALLOW_FAIL_OPEN) {
                warningSink.warn(
                        WARN_FAIL_OPEN,
                        "SimpleClaims tamed damage evaluation failed open: reason="
                                + decision.reason()
                                + ", detail="
                                + safeDetail(decision.detail())
                                + "."
                );
            }
            return decision;
        } catch (Throwable throwable) {
            return failOpen(false, null, "lookup-error", message(throwable));
        }
    }

    private boolean hasServerPermission(@Nonnull UUID attackerPlayerUuid,
                                        @Nonnull String permissionKey) {
        try {
            return serverPermissionBypass.isGranted(attackerPlayerUuid, permissionKey);
        } catch (Throwable throwable) {
            warningSink.warn(
                    WARN_PERMISSION,
                    "Tamework could not evaluate the configured server damage permission '"
                            + permissionKey
                            + "'; native SimpleClaims policy will still run: "
                            + message(throwable)
                            + "."
            );
            return false;
        }
    }

    @Nonnull
    private LegacySimpleClaimsPartyPermissionBypass.Result evaluateLegacyBypass(
            @Nonnull LegacySimpleClaimsPartyPermissionBypass legacyPartyBypass,
            @Nonnull String worldName,
            @Nonnull Vector3d targetPosition,
            @Nonnull UUID attackerPlayerUuid,
            @Nonnull String permissionKey) {
        try {
            return legacyPartyBypass.evaluate(worldName, targetPosition, attackerPlayerUuid, permissionKey);
        } catch (Throwable throwable) {
            return new LegacySimpleClaimsPartyPermissionBypass.Result(
                    LegacySimpleClaimsPartyPermissionBypass.Status.ERROR,
                    null,
                    message(throwable)
            );
        }
    }

    @Nonnull
    private SimpleClaimsDamageCapabilityResolver.Resolution resolveCapability() {
        if (closed.get()) {
            return SimpleClaimsDamageCapabilityResolver.Resolution.unavailable(
                    SimpleClaimsPluginState.ERROR,
                    SimpleClaimsPluginGeneration.NONE,
                    null,
                    "SimpleClaims damage policy is shut down."
            );
        }
        try {
            SimpleClaimsDamageCapabilityResolver.Resolution resolution = capabilityResolver.resolve();
            return resolution != null
                    ? resolution
                    : SimpleClaimsDamageCapabilityResolver.Resolution.unavailable(
                    SimpleClaimsPluginState.ERROR,
                    SimpleClaimsPluginGeneration.NONE,
                    null,
                    "SimpleClaims damage capability resolver returned null."
            );
        } catch (Throwable throwable) {
            return SimpleClaimsDamageCapabilityResolver.Resolution.unavailable(
                    SimpleClaimsPluginState.ERROR,
                    SimpleClaimsPluginGeneration.NONE,
                    null,
                    "SimpleClaims damage capability resolution failed: " + message(throwable)
            );
        }
    }

    /** Invalidates reflected SimpleClaims contracts after settings or plugin lifecycle changes. */
    public void onRuntimeSettingsChanged() {
        if (!closed.get()) {
            capabilityResolver.invalidate();
        }
    }

    /** Releases the optional plugin generation retained by the live resolver. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            capabilityResolver.close();
        }
    }

    @Nonnull
    private static SimpleClaimsDamageCapabilityResolver fixedResolver(
            @Nonnull NativeSimpleClaimsDamageAccess nativeAccess,
            @Nonnull LegacySimpleClaimsPartyPermissionBypass legacyPartyBypass) {
        SimpleClaimsDamageGeneration generation = SimpleClaimsDamageGeneration.fixed(nativeAccess, legacyPartyBypass);
        SimpleClaimsDamageCapabilityResolver.Resolution resolution =
                SimpleClaimsDamageCapabilityResolver.Resolution.ready(
                        SimpleClaimsPluginGeneration.NONE,
                        null,
                        generation
                );
        return () -> resolution;
    }

    @Nonnull
    private TamedDamageDecision failOpen(boolean claimAccessAvailable,
                                         @Nullable UUID claimPartyId,
                                         @Nonnull String reason,
                                         @Nullable String detail) {
        warningSink.warn(
                WARN_FAIL_OPEN,
                "SimpleClaims tamed damage evaluation failed open: reason="
                        + reason
                        + ", detail="
                        + safeDetail(detail)
                        + "."
        );
        return TamedDamageDecision.allowFailOpen(
                reason,
                claimAccessAvailable,
                claimPartyId,
                detail
        );
    }

    private static boolean isFinite(@Nullable Vector3d position) {
        return position != null
                && Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    @Nullable
    private static String normalizePermissionKey(@Nullable String permissionKey) {
        if (permissionKey == null) {
            return null;
        }
        String normalized = permissionKey.trim();
        return normalized.isBlank() ? null : normalized;
    }

    @Nonnull
    private static String safeDetail(@Nullable String detail) {
        return detail == null || detail.isBlank() ? "none" : detail;
    }

    @Nonnull
    private static String firstNonBlank(@Nullable String preferred, @Nonnull String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    @Nonnull
    private static String message(@Nullable Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
