package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimModDamageProvider;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import com.alechilles.alecstamework.settings.ClaimModChoice;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleClaimsTamedDamagePolicyTest {
    private static final UUID ATTACKER = UUID.randomUUID();
    private static final Vector3d POSITION = new Vector3d(16.5, 64.0, -2.0);

    @Test
    void disabledMasterAndProtectionNeverInvokeIntegration() throws Exception {
        AtomicInteger nativeCalls = new AtomicInteger();
        AtomicInteger serverPermissionCalls = new AtomicInteger();
        AtomicInteger legacyCalls = new AtomicInteger();
        SimpleClaimsTamedDamagePolicy policy = policy(
                nativeCalls,
                serverPermissionCalls,
                legacyCalls,
                false,
                LegacySimpleClaimsPartyPermissionBypass.Status.NOT_GRANTED,
                List.of()
        );

        TamedDamageDecision masterDisabled = policy.evaluateResolvedEligibility(
                TamedDamageOwnerPolicy.unowned(),
                TamedDamageTargetEligibilityResolver.Status.ELIGIBLE,
                "world",
                POSITION,
                ATTACKER,
                config(false, true)
        );
        TamedDamageDecision protectionDisabled = policy.evaluateResolvedEligibility(
                TamedDamageOwnerPolicy.unowned(),
                TamedDamageTargetEligibilityResolver.Status.ELIGIBLE,
                "world",
                POSITION,
                ATTACKER,
                config(true, false)
        );

        assertEquals(TamedDamageDecision.Status.ALLOW_SKIPPED, masterDisabled.status());
        assertEquals(TamedDamageDecision.Status.ALLOW_SKIPPED, protectionDisabled.status());
        assertEquals(0, nativeCalls.get());
        assertEquals(0, serverPermissionCalls.get());
        assertEquals(0, legacyCalls.get());
    }

    @Test
    void disabledMasterAndProtectionDoNotProbePluginLifecycle() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        SimpleClaimsTamedDamagePolicy policy = new SimpleClaimsTamedDamagePolicy(
                new TamedDamageTargetEligibilityResolver(),
                () -> {
                    resolutions.incrementAndGet();
                    return SimpleClaimsDamageCapabilityResolver.Resolution.unavailable(
                            SimpleClaimsPluginState.ABSENT,
                            SimpleClaimsPluginGeneration.NONE,
                            null,
                            "not installed"
                    );
                },
                (attacker, permission) -> false,
                (category, message) -> { }
        );

        policy.evaluateResolvedEligibility(
                TamedDamageOwnerPolicy.unowned(),
                TamedDamageTargetEligibilityResolver.Status.ELIGIBLE,
                "world",
                POSITION,
                ATTACKER,
                config(false, true)
        );
        policy.evaluateResolvedEligibility(
                TamedDamageOwnerPolicy.unowned(),
                TamedDamageTargetEligibilityResolver.Status.ELIGIBLE,
                "world",
                POSITION,
                ATTACKER,
                config(true, false)
        );

        assertEquals(0, resolutions.get());
    }

    @Test
    void serverPermissionBypassShortCircuitsLegacyAndNativePolicy() throws Exception {
        AtomicInteger nativeCalls = new AtomicInteger();
        AtomicInteger serverPermissionCalls = new AtomicInteger();
        AtomicInteger legacyCalls = new AtomicInteger();
        SimpleClaimsTamedDamagePolicy policy = policy(
                nativeCalls,
                serverPermissionCalls,
                legacyCalls,
                true,
                LegacySimpleClaimsPartyPermissionBypass.Status.GRANTED,
                List.of()
        );

        TamedDamageDecision decision = evaluateEligible(policy);

        assertTrue(decision.allowed());
        assertEquals(TamedDamageDecision.Status.ALLOW_ENFORCED, decision.status());
        assertEquals("server-permission-bypass", decision.reason());
        assertEquals(1, serverPermissionCalls.get());
        assertEquals(0, legacyCalls.get());
        assertEquals(0, nativeCalls.get());
    }

    @Test
    void exactLegacyRawPartyGrantIsTemporaryBypassWithWarning() throws Exception {
        AtomicInteger nativeCalls = new AtomicInteger();
        AtomicInteger serverPermissionCalls = new AtomicInteger();
        AtomicInteger legacyCalls = new AtomicInteger();
        List<String> warnings = new ArrayList<>();
        SimpleClaimsTamedDamagePolicy policy = policy(
                nativeCalls,
                serverPermissionCalls,
                legacyCalls,
                false,
                LegacySimpleClaimsPartyPermissionBypass.Status.GRANTED,
                warnings
        );

        TamedDamageDecision decision = evaluateEligible(policy);

        assertEquals("legacy-party-permission-bypass", decision.reason());
        assertEquals(1, legacyCalls.get());
        assertEquals(0, nativeCalls.get());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("deprecated raw-party"));
    }

    @Test
    void unownedTamedTargetUsesNativePolicyAndNativeErrorsFailOpen() throws Exception {
        AtomicReference<SimpleClaimsBreedingBridge.DamageAccessStatus> status =
                new AtomicReference<>(SimpleClaimsBreedingBridge.DamageAccessStatus.DENIED);
        List<String> warnings = new ArrayList<>();
        SimpleClaimsTamedDamagePolicy policy = new SimpleClaimsTamedDamagePolicy(
                new TamedDamageTargetEligibilityResolver(),
                (world, position, attacker, key) -> new SimpleClaimsBreedingBridge.DamageAccessResult(
                        status.get(),
                        null,
                        status.get().name()
                ),
                (attacker, key) -> false,
                (world, position, attacker, key) -> LegacySimpleClaimsPartyPermissionBypass.Result.notGranted(),
                (category, message) -> warnings.add(message)
        );

        TamedDamageDecision denied = evaluateEligible(policy);
        status.set(SimpleClaimsBreedingBridge.DamageAccessStatus.LOOKUP_ERROR);
        TamedDamageDecision failedOpen = evaluateEligible(policy);

        assertFalse(denied.allowed());
        assertEquals(TamedDamageDecision.Status.DENY_CLAIM, denied.status());
        assertTrue(failedOpen.allowed());
        assertEquals(TamedDamageDecision.Status.ALLOW_FAIL_OPEN, failedOpen.status());
        assertEquals(1, warnings.size());
    }

    @Test
    void ownedNonTamedAndUnattributedTargetsSkipWithoutIntegration() throws Exception {
        AtomicInteger nativeCalls = new AtomicInteger();
        SimpleClaimsTamedDamagePolicy policy = policy(
                nativeCalls,
                new AtomicInteger(),
                new AtomicInteger(),
                false,
                LegacySimpleClaimsPartyPermissionBypass.Status.NOT_GRANTED,
                List.of()
        );
        TwGlobalConfig config = config(true, true);

        TamedDamageDecision nonTamed = policy.evaluateResolvedEligibility(
                new TamedDamageOwnerPolicy(UUID.randomUUID(), false, false, false, false),
                TamedDamageTargetEligibilityResolver.Status.INELIGIBLE,
                "world",
                POSITION,
                ATTACKER,
                config
        );
        TamedDamageDecision environment = policy.evaluateResolvedEligibility(
                TamedDamageOwnerPolicy.unowned(),
                TamedDamageTargetEligibilityResolver.Status.ELIGIBLE,
                "world",
                POSITION,
                null,
                config
        );

        assertEquals("target-not-tamed", nonTamed.reason());
        assertEquals("attacker-unattributed", environment.reason());
        assertEquals(0, nativeCalls.get());
    }

    @Test
    void runtimeAndRawApiResolveTheSameReplacementGenerationWithoutRestart() throws Exception {
        UUID partyId = UUID.randomUUID();
        AtomicReference<SimpleClaimsDamageCapabilityResolver.Resolution> current = new AtomicReference<>();
        current.set(SimpleClaimsDamageCapabilityResolver.Resolution.ready(
                new SimpleClaimsPluginGeneration("plugin-a", "loader-a", 1L),
                "1.0.38",
                policyGeneration(SimpleClaimsBreedingBridge.DamageAccessStatus.DENIED, partyId)
        ));
        SimpleClaimsTamedDamagePolicy policy = new SimpleClaimsTamedDamagePolicy(
                new TamedDamageTargetEligibilityResolver(),
                current::get,
                (attacker, permission) -> false,
                (category, message) -> { }
        );

        TamedDamageDecision runtimeDenied = evaluateEligible(policy);
        SimpleClaimsRawAccessDecision rawDenied = policy.evaluateRawClaimAccess(
                "world", POSITION, ATTACKER, config(true, true));

        current.set(SimpleClaimsDamageCapabilityResolver.Resolution.ready(
                new SimpleClaimsPluginGeneration("plugin-b", "loader-b", 2L),
                "1.0.39",
                policyGeneration(SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED, partyId)
        ));
        TamedDamageDecision runtimeAllowed = evaluateEligible(policy);
        SimpleClaimsRawAccessDecision rawAllowed = policy.evaluateRawClaimAccess(
                "world", POSITION, ATTACKER, config(true, true));

        assertEquals(TamedDamageDecision.Status.DENY_CLAIM, runtimeDenied.status());
        assertEquals(SimpleClaimsRawAccessDecision.Status.DENIED, rawDenied.status());
        assertEquals(TamedDamageDecision.Status.ALLOW_ENFORCED, runtimeAllowed.status());
        assertEquals(SimpleClaimsRawAccessDecision.Status.ALLOWED, rawAllowed.status());
    }

    @Test
    void absentDamageProviderFailsOpenThenReadyGenerationEnforces() throws Exception {
        AtomicReference<SimpleClaimsDamageCapabilityResolver.Resolution> current = new AtomicReference<>(
                SimpleClaimsDamageCapabilityResolver.Resolution.unavailable(
                        SimpleClaimsPluginState.ABSENT,
                        SimpleClaimsPluginGeneration.NONE,
                        null,
                        "not installed"
                )
        );
        SimpleClaimsTamedDamagePolicy policy = new SimpleClaimsTamedDamagePolicy(
                new TamedDamageTargetEligibilityResolver(),
                current::get,
                (attacker, permission) -> false,
                (category, message) -> { }
        );

        TamedDamageDecision absent = evaluateEligible(policy);
        current.set(SimpleClaimsDamageCapabilityResolver.Resolution.ready(
                new SimpleClaimsPluginGeneration("plugin-a", "loader-a", 1L),
                "1.0.38",
                new SimpleClaimsDamageGeneration(
                        null,
                        (world, position, attacker, key) -> new SimpleClaimsBreedingBridge.DamageAccessResult(
                                SimpleClaimsBreedingBridge.DamageAccessStatus.DENIED, null, null),
                        (world, position, attacker, key) ->
                                LegacySimpleClaimsPartyPermissionBypass.Result.notGranted(),
                        (world, position) -> new SimpleClaimsClaimIdentityAccess.Result(
                                SimpleClaimsClaimIdentityAccess.Status.NO_CLAIM, null, null),
                        true,
                        true,
                        null
                )
        ));
        TamedDamageDecision ready = evaluateEligible(policy);

        assertEquals(TamedDamageDecision.Status.ALLOW_FAIL_OPEN, absent.status());
        assertEquals(TamedDamageDecision.Status.DENY_CLAIM, ready.status());
    }

    @Test
    void questLinesChoiceUsesItsDamageRuleAndFailsOpenOnErrors() throws Exception {
        FakeClaimMod questLines = new FakeClaimMod();
        AtomicInteger simpleClaimsResolutions = new AtomicInteger();
        List<String> warnings = new ArrayList<>();
        SimpleClaimsTamedDamagePolicy policy = claimModPolicy(
                questLines, simpleClaimsResolutions, mod -> true, false, warnings);
        TwGlobalConfig config = config(true, true, "QuestLinesClaims");

        questLines.damage = ClaimModDamageProvider.DamageResult.Status.DENIED;
        TamedDamageDecision denied = evaluateEligible(policy, config);
        questLines.damage = ClaimModDamageProvider.DamageResult.Status.ERROR;
        TamedDamageDecision failedOpen = evaluateEligible(policy, config);

        assertEquals(TamedDamageDecision.Status.DENY_CLAIM, denied.status());
        assertEquals(TamedDamageDecision.Status.ALLOW_FAIL_OPEN, failedOpen.status());
        assertEquals(2, questLines.damageCalls.get());
        assertEquals(0, simpleClaimsResolutions.get());
        assertEquals(1, warnings.size());
    }

    @Test
    void serverPermissionBypassAppliesBeforeQuestLines() throws Exception {
        FakeClaimMod questLines = new FakeClaimMod();
        questLines.damage = ClaimModDamageProvider.DamageResult.Status.DENIED;
        SimpleClaimsTamedDamagePolicy policy = claimModPolicy(
                questLines, new AtomicInteger(), mod -> true, true, new ArrayList<>());

        TamedDamageDecision decision = evaluateEligible(policy, config(true, true, "QuestLinesClaims"));

        assertEquals("server-permission-bypass", decision.reason());
        assertEquals(0, questLines.damageCalls.get());
    }

    @Test
    void automaticChoiceUsesQuestLinesOnlyWhenSimpleClaimsIsNotInstalled() throws Exception {
        FakeClaimMod questLines = new FakeClaimMod();
        questLines.damage = ClaimModDamageProvider.DamageResult.Status.DENIED;
        AtomicInteger simpleClaimsResolutions = new AtomicInteger();
        SimpleClaimsTamedDamagePolicy questLinesOnly = claimModPolicy(questLines, simpleClaimsResolutions,
                mod -> mod == ClaimModChoice.QUESTLINES_CLAIMS, false, new ArrayList<>());
        SimpleClaimsTamedDamagePolicy bothInstalled = claimModPolicy(questLines, simpleClaimsResolutions,
                mod -> true, false, new ArrayList<>());

        TamedDamageDecision viaQuestLines = evaluateEligible(questLinesOnly, config(true, true, null));
        TamedDamageDecision viaSimpleClaims = evaluateEligible(bothInstalled, config(true, true, null));

        assertEquals(TamedDamageDecision.Status.DENY_CLAIM, viaQuestLines.status());
        assertEquals(1, questLines.damageCalls.get());
        assertEquals(TamedDamageDecision.Status.ALLOW_ENFORCED, viaSimpleClaims.status());
        assertEquals(1, simpleClaimsResolutions.get());
    }

    @Test
    void rawClaimAccessFollowsTheQuestLinesClaimAndDamageRule() throws Exception {
        FakeClaimMod questLines = new FakeClaimMod();
        SimpleClaimsTamedDamagePolicy policy = claimModPolicy(
                questLines, new AtomicInteger(), mod -> true, false, new ArrayList<>());
        TwGlobalConfig config = config(true, true, "QuestLinesClaims");

        questLines.lookup = ClaimLookupResult.noClaim();
        SimpleClaimsRawAccessDecision outside = policy.evaluateRawClaimAccess("world", POSITION, ATTACKER, config);
        questLines.lookup = ClaimLookupResult.found("questlines:3", 2);
        questLines.damage = ClaimModDamageProvider.DamageResult.Status.DENIED;
        SimpleClaimsRawAccessDecision denied = policy.evaluateRawClaimAccess("world", POSITION, ATTACKER, config);

        assertEquals(SimpleClaimsRawAccessDecision.Status.ALLOWED, outside.status());
        assertEquals("outside-claim", outside.reason());
        assertEquals(SimpleClaimsRawAccessDecision.Status.DENIED, denied.status());
        assertFalse(denied.allowed());
    }

    private static SimpleClaimsTamedDamagePolicy claimModPolicy(FakeClaimMod questLines,
                                                                AtomicInteger simpleClaimsResolutions,
                                                                Predicate<ClaimModChoice> installed,
                                                                boolean serverPermissionGranted,
                                                                List<String> warnings) {
        SimpleClaimsDamageCapabilityResolver.Resolution simpleClaims = SimpleClaimsDamageCapabilityResolver.Resolution.ready(
                new SimpleClaimsPluginGeneration("plugin-a", "loader-a", 1L),
                "1.0.38",
                policyGeneration(SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED, UUID.randomUUID())
        );
        return new SimpleClaimsTamedDamagePolicy(
                new TamedDamageTargetEligibilityResolver(),
                () -> {
                    simpleClaimsResolutions.incrementAndGet();
                    return simpleClaims;
                },
                (attacker, permission) -> serverPermissionGranted,
                (category, message) -> warnings.add(message),
                questLines,
                installed
        );
    }

    /** A claim mod read through the plain claim and damage-rule seam, as QuestLines Claims is. */
    private static final class FakeClaimMod implements ClaimModDamageProvider {
        private final AtomicInteger damageCalls = new AtomicInteger();
        private ClaimLookupResult lookup = ClaimLookupResult.noClaim();
        private DamageResult.Status damage = DamageResult.Status.ALLOWED;

        @Override
        public String unavailableReason() {
            return null;
        }

        @Override
        public ClaimLookupResult lookupClaim(String worldName, Vector3d position) {
            return lookup;
        }

        @Override
        public DamageResult evaluateTamedDamage(String worldName, Vector3d position, UUID attackerPlayerUuid) {
            damageCalls.incrementAndGet();
            return new DamageResult(damage, damage.name());
        }
    }

    private static SimpleClaimsDamageGeneration policyGeneration(
            SimpleClaimsBreedingBridge.DamageAccessStatus status,
            UUID partyId) {
        return new SimpleClaimsDamageGeneration(
                null,
                (world, position, attacker, key) ->
                        new SimpleClaimsBreedingBridge.DamageAccessResult(status, partyId, null),
                (world, position, attacker, key) -> LegacySimpleClaimsPartyPermissionBypass.Result.notGranted(),
                (world, position) -> new SimpleClaimsClaimIdentityAccess.Result(
                        SimpleClaimsClaimIdentityAccess.Status.CLAIM_FOUND,
                        partyId,
                        null
                ),
                true,
                true,
                null
        );
    }

    private static TamedDamageDecision evaluateEligible(SimpleClaimsTamedDamagePolicy policy) throws Exception {
        return evaluateEligible(policy, config(true, true));
    }

    private static TamedDamageDecision evaluateEligible(SimpleClaimsTamedDamagePolicy policy,
                                                        TwGlobalConfig config) {
        return policy.evaluateResolvedEligibility(
                TamedDamageOwnerPolicy.unowned(),
                TamedDamageTargetEligibilityResolver.Status.ELIGIBLE,
                "world",
                POSITION,
                ATTACKER,
                config
        );
    }

    private static SimpleClaimsTamedDamagePolicy policy(
            AtomicInteger nativeCalls,
            AtomicInteger serverPermissionCalls,
            AtomicInteger legacyCalls,
            boolean serverPermissionGranted,
            LegacySimpleClaimsPartyPermissionBypass.Status legacyStatus,
            List<String> warnings) {
        return new SimpleClaimsTamedDamagePolicy(
                new TamedDamageTargetEligibilityResolver(),
                (world, position, attacker, key) -> {
                    nativeCalls.incrementAndGet();
                    return new SimpleClaimsBreedingBridge.DamageAccessResult(
                            SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED,
                            null,
                            null
                    );
                },
                (attacker, key) -> {
                    serverPermissionCalls.incrementAndGet();
                    return serverPermissionGranted;
                },
                (world, position, attacker, key) -> {
                    legacyCalls.incrementAndGet();
                    return new LegacySimpleClaimsPartyPermissionBypass.Result(legacyStatus, null, null);
                },
                (category, message) -> warnings.add(message)
        );
    }

    private static TwGlobalConfig config(boolean enabled, boolean protect) throws Exception {
        return config(enabled, protect, null);
    }

    private static TwGlobalConfig config(boolean enabled, boolean protect, String claimMod) throws Exception {
        TwGlobalConfig config = TwGlobalConfig.defaultConfig();
        setField(config, "simpleClaimsEnabled", enabled);
        setField(config, "simpleClaimsDamageProtectTamedFromNonMembers", protect);
        setField(config, "simpleClaimsDamageAllowDamagePermissionKey", "tamework.damage_tamed_claim_npc");
        setField(config, "simpleClaimsProvider", claimMod);
        return config;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
