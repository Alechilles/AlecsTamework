package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TamedDamagePolicyAdapterTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OUTSIDER = UUID.randomUUID();

    private final TamedDamagePolicyAdapter adapter = new TamedDamagePolicyAdapter();

    @Test
    void ownerProtectionPrecedesDisabledClaimProtection() {
        TamedDamageDecision decision = adapter.evaluatePreconditions(
                new TamedDamageOwnerPolicy(OWNER, true, false, false),
                OWNER,
                false,
                false,
                TamedDamageTargetEligibilityResolver.Status.INELIGIBLE
        ).orElseThrow();

        assertFalse(decision.allowed());
        assertEquals(TamedDamageDecision.Status.DENY_OWNER, decision.status());
        assertEquals("block-owner-damage", decision.reason());
    }

    @Test
    void invulnerableOwnerPolicyStillAppliesToUnattributedDamage() {
        TamedDamageDecision decision = adapter.evaluatePreconditions(
                new TamedDamageOwnerPolicy(OWNER, false, false, true),
                null,
                true,
                true,
                TamedDamageTargetEligibilityResolver.Status.ELIGIBLE
        ).orElseThrow();

        assertEquals(TamedDamageDecision.Status.DENY_OWNER, decision.status());
        assertEquals("invulnerable-if-owned", decision.reason());
    }

    @Test
    void ownedButNotTamedTargetSkipsClaimPolicy() {
        TamedDamageDecision decision = adapter.evaluatePreconditions(
                new TamedDamageOwnerPolicy(OWNER, false, false, false),
                OUTSIDER,
                true,
                true,
                TamedDamageTargetEligibilityResolver.Status.INELIGIBLE
        ).orElseThrow();

        assertTrue(decision.allowed());
        assertEquals(TamedDamageDecision.Status.ALLOW_SKIPPED, decision.status());
        assertEquals("target-not-tamed", decision.reason());
    }

    @Test
    void dormantTargetIsExplicitlyUnavailable() {
        TamedDamageDecision decision = adapter.evaluatePreconditions(
                TamedDamageOwnerPolicy.unowned(),
                OUTSIDER,
                true,
                true,
                TamedDamageTargetEligibilityResolver.Status.LIVE_TARGET_REQUIRED
        ).orElseThrow();

        assertTrue(decision.allowed());
        assertEquals(TamedDamageDecision.Status.UNAVAILABLE, decision.status());
        assertEquals("live-target-required", decision.reason());
    }

    @Test
    void nativeDenyAndErrorsMapToDenyAndFailOpen() {
        UUID partyId = UUID.randomUUID();
        TamedDamageDecision denied = adapter.mapNative(new SimpleClaimsBreedingBridge.DamageAccessResult(
                SimpleClaimsBreedingBridge.DamageAccessStatus.DENIED,
                partyId,
                "denied"
        ));
        TamedDamageDecision failed = adapter.mapNative(new SimpleClaimsBreedingBridge.DamageAccessResult(
                SimpleClaimsBreedingBridge.DamageAccessStatus.LOOKUP_ERROR,
                partyId,
                "boom"
        ));

        assertFalse(denied.allowed());
        assertEquals(TamedDamageDecision.Status.DENY_CLAIM, denied.status());
        assertTrue(failed.allowed());
        assertEquals(TamedDamageDecision.Status.ALLOW_FAIL_OPEN, failed.status());
        assertTrue(failed.claimAccessAvailable());
    }
}
