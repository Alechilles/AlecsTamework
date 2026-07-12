package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.api.DamagePolicyDecisionView;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsDamageBridgeFixture;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsDamageBridgeFixture.Mode;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.TestTwGlobalAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Shared fixtures and expected outcomes for the runtime/API SimpleClaims damage adapter matrix. */
public final class SimpleClaimsDamageAdapterMatrix {
    public static final String WORLD_NAME = "damage-adapter-world";
    public static final Vector3d TARGET_POSITION = new Vector3d(1.5, 64.0, 1.5);
    public static final UUID ATTACKER =
            UUID.fromString("00000000-0000-0000-0000-00000000a101");
    public static final UUID TARGET_OWNER =
            UUID.fromString("00000000-0000-0000-0000-00000000a102");
    private SimpleClaimsDamageAdapterMatrix() {
    }

    public enum SourceKind {
        DIRECT,
        PROJECTILE,
        ENVIRONMENT
    }

    public enum ParityExpectation {
        MATCH,
        RUNTIME_EVENT_ALREADY_CANCELLED,
        API_TARGET_NOT_LIVE
    }

    /** One end-to-end adapter case with explicit runtime mutation and public API expectations. */
    public record Scenario(
            @Nonnull String name,
            @Nonnull Mode mode,
            @Nonnull SourceKind sourceKind,
            boolean protectionEnabled,
            boolean targetTamed,
            boolean targetOwned,
            boolean ownerPrecedence,
            boolean initiallyCancelled,
            boolean apiTargetLive,
            boolean expectedRuntimeCancelled,
            float expectedRuntimeAmount,
            @Nonnull DamagePolicyDecisionView.Status expectedApiStatus,
            @Nonnull String expectedApiReason,
            int expectedNativeCalls,
            @Nonnull ParityExpectation parityExpectation) {

        @Override
        public String toString() {
            return name;
        }

        @Nullable
        public UUID apiAttackerUuid() {
            return sourceKind == SourceKind.ENVIRONMENT ? null : ATTACKER;
        }

        @Nullable
        public UUID targetOwnerUuid() {
            if (!targetOwned) {
                return null;
            }
            return ownerPrecedence ? ATTACKER : TARGET_OWNER;
        }

        public boolean expectedApiAllowed() {
            return expectedApiStatus != DamagePolicyDecisionView.Status.DENIED_OWNER_PROTECTION
                    && expectedApiStatus != DamagePolicyDecisionView.Status.DENIED_CLAIM_PROTECTION;
        }

        public boolean expectedClaimPartyIdentity() {
            if (expectedApiStatus != DamagePolicyDecisionView.Status.ALLOWED
                    && expectedApiStatus != DamagePolicyDecisionView.Status.DENIED_CLAIM_PROTECTION) {
                return false;
            }
            return switch (mode) {
                case ADMIN, MEMBER_ALLOWED, MEMBER_DENIED, PLAYER_ALLY_ALLOWED,
                        PARTY_ALLY_ALLOWED, OUTSIDER_ALLOWED, OUTSIDER_DENIED -> true;
                default -> false;
            };
        }
    }

    @Nonnull
    public static List<Scenario> scenarios() {
        return List.of(
                scenario("owner-component-precedes-link-and-name", Mode.OUTSIDER_ALLOWED, SourceKind.DIRECT,
                        true, true, true, true, false, false,
                        true, 0.0f, DamagePolicyDecisionView.Status.DENIED_OWNER_PROTECTION,
                        "block-owner-damage", 0, ParityExpectation.MATCH),
                scenario("no-claim-allows", Mode.NO_CLAIM, SourceKind.DIRECT,
                        true, true, true, false, false, true,
                        false, 10.0f, DamagePolicyDecisionView.Status.ALLOWED,
                        "native-policy-allowed", 2, ParityExpectation.MATCH),
                scenario("full-world-protection-denies", Mode.FULL_WORLD_PROTECTED, SourceKind.DIRECT,
                        true, true, true, false, false, true,
                        true, 0.0f, DamagePolicyDecisionView.Status.DENIED_CLAIM_PROTECTION,
                        "claim-protection-denied", 2, ParityExpectation.MATCH),
                scenario("admin-override-allows", Mode.ADMIN, SourceKind.DIRECT,
                        true, true, true, false, false, true,
                        false, 10.0f, DamagePolicyDecisionView.Status.ALLOWED,
                        "native-policy-allowed", 2, ParityExpectation.MATCH),
                scenario("member-permission-allows", Mode.MEMBER_ALLOWED, SourceKind.DIRECT,
                        true, true, true, false, false, true,
                        false, 10.0f, DamagePolicyDecisionView.Status.ALLOWED,
                        "native-policy-allowed", 2, ParityExpectation.MATCH),
                scenario("member-without-permission-denies", Mode.MEMBER_DENIED, SourceKind.DIRECT,
                        true, true, true, false, false, true,
                        true, 0.0f, DamagePolicyDecisionView.Status.DENIED_CLAIM_PROTECTION,
                        "claim-protection-denied", 2, ParityExpectation.MATCH),
                scenario("direct-player-ally-allows", Mode.PLAYER_ALLY_ALLOWED, SourceKind.DIRECT,
                        true, true, true, false, false, true,
                        false, 10.0f, DamagePolicyDecisionView.Status.ALLOWED,
                        "native-policy-allowed", 2, ParityExpectation.MATCH),
                scenario("projectile-party-ally-uses-resolved-party", Mode.PARTY_ALLY_ALLOWED,
                        SourceKind.PROJECTILE, true, true, true, false, false, true,
                        false, 10.0f, DamagePolicyDecisionView.Status.ALLOWED,
                        "native-policy-allowed", 2, ParityExpectation.MATCH),
                scenario("outsider-fallback-allows", Mode.OUTSIDER_ALLOWED, SourceKind.DIRECT,
                        true, true, true, false, false, true,
                        false, 10.0f, DamagePolicyDecisionView.Status.ALLOWED,
                        "native-policy-allowed", 2, ParityExpectation.MATCH),
                scenario("outsider-fallback-denies", Mode.OUTSIDER_DENIED, SourceKind.DIRECT,
                        true, true, true, false, false, true,
                        true, 0.0f, DamagePolicyDecisionView.Status.DENIED_CLAIM_PROTECTION,
                        "claim-protection-denied", 2, ParityExpectation.MATCH),
                scenario("provider-failure-fails-open", Mode.PROVIDER_FAILURE, SourceKind.DIRECT,
                        true, true, true, false, false, true,
                        false, 10.0f, DamagePolicyDecisionView.Status.ALLOWED_FAIL_OPEN,
                        "lookup-error", 2, ParityExpectation.MATCH),
                scenario("disabled-protection-skips-provider", Mode.OUTSIDER_DENIED, SourceKind.DIRECT,
                        false, true, true, false, false, true,
                        false, 10.0f, DamagePolicyDecisionView.Status.ALLOWED_SKIPPED,
                        "damage-protection-disabled", 0, ParityExpectation.MATCH),
                scenario("environmental-damage-is-unattributed", Mode.OUTSIDER_DENIED, SourceKind.ENVIRONMENT,
                        true, true, true, false, false, true,
                        false, 10.0f, DamagePolicyDecisionView.Status.ALLOWED_SKIPPED,
                        "attacker-unattributed", 0, ParityExpectation.MATCH),
                scenario("already-cancelled-runtime-event-is-not-reevaluated", Mode.OUTSIDER_DENIED,
                        SourceKind.DIRECT, true, true, true, false, true, true,
                        true, 10.0f, DamagePolicyDecisionView.Status.DENIED_CLAIM_PROTECTION,
                        "claim-protection-denied", 1, ParityExpectation.RUNTIME_EVENT_ALREADY_CANCELLED),
                scenario("tamed-unowned-legacy-target-is-protected", Mode.OUTSIDER_DENIED, SourceKind.DIRECT,
                        true, true, false, false, false, true,
                        true, 0.0f, DamagePolicyDecisionView.Status.DENIED_CLAIM_PROTECTION,
                        "claim-protection-denied", 2, ParityExpectation.MATCH),
                scenario("owned-non-tamed-target-skips", Mode.OUTSIDER_DENIED, SourceKind.DIRECT,
                        true, false, true, false, false, true,
                        false, 10.0f, DamagePolicyDecisionView.Status.ALLOWED_SKIPPED,
                        "target-not-tamed", 0, ParityExpectation.MATCH),
                scenario("non-live-api-target-is-unavailable", Mode.OUTSIDER_DENIED, SourceKind.DIRECT,
                        true, true, true, false, false, false,
                        true, 0.0f, DamagePolicyDecisionView.Status.UNAVAILABLE,
                        "live-target-required", 1, ParityExpectation.API_TARGET_NOT_LIVE)
        );
    }

    private static Scenario scenario(String name,
                                     Mode mode,
                                     SourceKind sourceKind,
                                     boolean protectionEnabled,
                                     boolean targetTamed,
                                     boolean targetOwned,
                                     boolean ownerPrecedence,
                                     boolean initiallyCancelled,
                                     boolean apiTargetLive,
                                     boolean runtimeCancelled,
                                     float runtimeAmount,
                                     DamagePolicyDecisionView.Status apiStatus,
                                     String apiReason,
                                     int nativeCalls,
                                     ParityExpectation parityExpectation) {
        return new Scenario(
                name, mode, sourceKind, protectionEnabled, targetTamed, targetOwned,
                ownerPrecedence, initiallyCancelled, apiTargetLive, runtimeCancelled, runtimeAmount,
                apiStatus, apiReason, nativeCalls, parityExpectation
        );
    }

    /** Installs the native-shaped SimpleClaims generation used by both adapters. */
    public static final class PolicyFixture implements AutoCloseable {
        private final SimpleClaimsDamageBridgeFixture bridgeFixture;
        private final SimpleClaimsTamedDamagePolicy policy;

        private PolicyFixture(@Nonnull Scenario scenario) {
            bridgeFixture = SimpleClaimsDamageBridgeFixture.open(scenario.mode(), ATTACKER);
            policy = new SimpleClaimsTamedDamagePolicy(
                    new TamedDamageTargetEligibilityResolver(),
                    bridgeFixture.bridge()::evaluateDamageAccess,
                    (attacker, permission) -> false,
                    (world, position, attacker, permission) ->
                            LegacySimpleClaimsPartyPermissionBypass.Result.notGranted(),
                    (category, message) -> { }
            );
        }

        @Nonnull
        public static PolicyFixture open(@Nonnull Scenario scenario) {
            return new PolicyFixture(scenario);
        }

        @Nonnull
        public SimpleClaimsTamedDamagePolicy policy() {
            return policy;
        }

        public int nativeCalls() {
            return bridgeFixture.nativeCalls();
        }

        @Nullable
        public UUID lastPartyPermissionSubject() {
            return bridgeFixture.lastPartyPermissionSubject();
        }

        @Nullable
        public UUID lastPlayerPermissionSubject() {
            return bridgeFixture.lastPlayerPermissionSubject();
        }

        @Override
        public void close() {
            policy.close();
            bridgeFixture.close();
        }
    }

    /** Installs one deterministic TwGlobalConfig for both adapter calls. */
    public static final class GlobalConfigScope implements AutoCloseable {
        private final Object oldStore;
        private final Object oldActive;
        private final boolean oldCacheDirty;
        private final boolean oldInheritanceDirty;

        private GlobalConfigScope(Object oldStore,
                                  Object oldActive,
                                  boolean oldCacheDirty,
                                  boolean oldInheritanceDirty) {
            this.oldStore = oldStore;
            this.oldActive = oldActive;
            this.oldCacheDirty = oldCacheDirty;
            this.oldInheritanceDirty = oldInheritanceDirty;
        }

        @Nonnull
        public static GlobalConfigScope install(@Nonnull Scenario scenario) throws Exception {
            Field storeField = staticField(TwGlobalConfig.class, "ASSET_STORE");
            Field activeField = staticField(TwGlobalConfig.class, "ACTIVE_CONFIG");
            Field dirtyField = staticField(TwGlobalConfig.class, "CACHE_DIRTY");
            Field inheritanceDirtyField = staticField(TwGlobalConfig.class, "INHERITANCE_CACHE_DIRTY");
            GlobalConfigScope scope = new GlobalConfigScope(
                    storeField.get(null),
                    activeField.get(null),
                    dirtyField.getBoolean(null),
                    inheritanceDirtyField.getBoolean(null)
            );

            TwGlobalConfig config = TwGlobalConfig.defaultConfig();
            setField(config, "id", "Damage_Adapter_Matrix");
            setField(config, "priority", Integer.MAX_VALUE);
            setField(config, "simpleClaimsEnabled", true);
            setField(config, "simpleClaimsDamageProtectTamedFromNonMembers", scenario.protectionEnabled());
            setField(config, "simpleClaimsSectionDefined", true);
            setField(config, "blockOwnerDamage", scenario.ownerPrecedence());
            setField(config, "blockAllPlayerDamageIfOwned", false);
            setField(config, "invulnerableIfOwned", false);

            DefaultAssetMap<String, TwGlobalConfig> map = new DefaultAssetMap<>(Map.of(config.getId(), config));
            AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> store =
                    new TestTwGlobalAssetStore(map);
            storeField.set(null, store);
            activeField.set(null, null);
            dirtyField.setBoolean(null, true);
            inheritanceDirtyField.setBoolean(null, true);
            return scope;
        }

        @Override
        public void close() throws Exception {
            staticField(TwGlobalConfig.class, "ASSET_STORE").set(null, oldStore);
            staticField(TwGlobalConfig.class, "ACTIVE_CONFIG").set(null, oldActive);
            staticField(TwGlobalConfig.class, "CACHE_DIRTY").setBoolean(null, oldCacheDirty);
            staticField(TwGlobalConfig.class, "INHERITANCE_CACHE_DIRTY")
                    .setBoolean(null, oldInheritanceDirty);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
