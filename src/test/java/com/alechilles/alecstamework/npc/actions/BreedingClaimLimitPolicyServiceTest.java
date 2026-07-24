package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests population-cap policy behavior for claim and per-player breeding limits. */
class BreedingClaimLimitPolicyServiceTest {

    @Test
    void noClaimAllowsBreedingWhenClaimIsNotRequired() throws Exception {
        TwGlobalConfig global = globalSettings(true, 0, 0, false);
        BreedingClaimLimitPolicyService.Decision decision = BreedingClaimLimitPolicyService.evaluateResolved(
                global,
                new BreedingClaimLimitPolicyService.ResolvedClaim(
                        BreedingClaimLimitPolicyService.ClaimResolutionStatus.NO_CLAIM,
                        null,
                        0,
                        null
                ),
                0,
                0
        );

        assertTrue(decision.allowed());
        assertFalse(decision.capEnforced());
    }

    @Test
    void noClaimDeniesBreedingWhenClaimIsRequired() throws Exception {
        TwGlobalConfig global = globalSettings(true, 0, 0, true);
        BreedingClaimLimitPolicyService.Decision decision = BreedingClaimLimitPolicyService.evaluateResolved(
                global,
                new BreedingClaimLimitPolicyService.ResolvedClaim(
                        BreedingClaimLimitPolicyService.ClaimResolutionStatus.NO_CLAIM,
                        null,
                        0,
                        null
                ),
                0,
                0
        );

        assertFalse(decision.allowed());
        assertEquals("claim-required", decision.reason());
    }

    @Test
    void noClaimAllowsTameAcquisitionEvenWhenBreedingRequiresClaim() throws Exception {
        TwGlobalConfig global = globalSettings(true, 2, 10, true);
        BreedingClaimLimitPolicyService.Decision decision =
                TameClaimLimitPolicyService.evaluateResolved(
                        global,
                        new BreedingClaimLimitPolicyService.ResolvedClaim(
                                BreedingClaimLimitPolicyService.ClaimResolutionStatus.NO_CLAIM,
                                null,
                                0,
                                null
                        ),
                        0
                );

        assertTrue(decision.allowed());
        assertEquals("outside-claim", decision.reason());
    }

    @Test
    void chunkScaledCapUsesClaimSize() throws Exception {
        TwGlobalConfig global = globalSettings(true, 3, 0, false);
        BreedingClaimLimitPolicyService.ClaimReservationKey key =
                new BreedingClaimLimitPolicyService.ClaimReservationKey("world", UUID.randomUUID());
        BreedingClaimLimitPolicyService.Decision decision = BreedingClaimLimitPolicyService.evaluateResolved(
                global,
                new BreedingClaimLimitPolicyService.ResolvedClaim(
                        BreedingClaimLimitPolicyService.ClaimResolutionStatus.CLAIM_FOUND,
                        key,
                        4,
                        null
                ),
                10,
                0
        );

        assertTrue(decision.allowed());
        assertTrue(decision.capEnforced());
        assertEquals(12, decision.effectiveCap());
        assertEquals(2, decision.remainingHeadroom());
    }

    @Test
    void totalCapActsAsHardCap() throws Exception {
        TwGlobalConfig global = globalSettings(true, 0, 5, false);
        BreedingClaimLimitPolicyService.Decision decision = BreedingClaimLimitPolicyService.evaluateResolved(
                global,
                claimFound(3),
                5,
                0
        );

        assertFalse(decision.allowed());
        assertEquals("claim-cap-reached", decision.reason());
        assertEquals(5, decision.effectiveCap());
    }

    @Test
    void totalCapBlocksTameAcquisitionAtClaimLimit() throws Exception {
        TwGlobalConfig global = globalSettings(true, 0, 5, false);
        BreedingClaimLimitPolicyService.Decision decision =
                TameClaimLimitPolicyService.evaluateResolved(
                        global,
                        claimFound(3),
                        5
                );

        assertFalse(decision.allowed());
        assertEquals("claim-cap-reached", decision.reason());
        assertEquals(5, decision.effectiveCap());
        assertEquals(5, decision.currentCount());
    }

    @Test
    void lowerOfChunkAndTotalCapsWins() throws Exception {
        TwGlobalConfig global = globalSettings(true, 3, 7, false);
        BreedingClaimLimitPolicyService.Decision decision = BreedingClaimLimitPolicyService.evaluateResolved(
                global,
                claimFound(5),
                6,
                0
        );

        assertTrue(decision.allowed());
        assertEquals(7, decision.effectiveCap());
        assertEquals(1, decision.remainingHeadroom());
    }

    @Test
    void lookupFailuresAreDeniedFailClosed() throws Exception {
        TwGlobalConfig global = globalSettings(true, 2, 10, false);
        BreedingClaimLimitPolicyService.Decision unavailableDecision = BreedingClaimLimitPolicyService.evaluateResolved(
                global,
                new BreedingClaimLimitPolicyService.ResolvedClaim(
                        BreedingClaimLimitPolicyService.ClaimResolutionStatus.UNAVAILABLE,
                        null,
                        0,
                        "missing dependency"
                ),
                0,
                0
        );
        BreedingClaimLimitPolicyService.Decision errorDecision = BreedingClaimLimitPolicyService.evaluateResolved(
                global,
                new BreedingClaimLimitPolicyService.ResolvedClaim(
                        BreedingClaimLimitPolicyService.ClaimResolutionStatus.ERROR,
                        null,
                        0,
                        "lookup error"
                ),
                0,
                0
        );

        assertFalse(unavailableDecision.allowed());
        assertFalse(errorDecision.allowed());
        assertEquals("simpleclaims-lookup-error", unavailableDecision.reason());
        assertEquals("simpleclaims-lookup-error", errorDecision.reason());
    }

    /** Regression: the limiting cap must retain both ephemeral sweep reservation keys. */
    @Test
    void combinedCapRetainsClaimAndOwnerSweepKeys() {
        BreedingClaimLimitPolicyService.ClaimReservationKey claimKey =
                new BreedingClaimLimitPolicyService.ClaimReservationKey(
                        "world", UUID.randomUUID()
                );
        BreedingClaimLimitPolicyService.PlayerReservationKey ownerKey =
                BreedingClaimLimitPolicyService.PlayerReservationKey.perWorld(
                        "world", UUID.randomUUID()
                );
        BreedingClaimLimitPolicyService.Decision claim =
                BreedingClaimLimitPolicyService.Decision.allowWithCap(
                        BreedingClaimLimitPolicyService.ConstraintType.CLAIM,
                        10,
                        8,
                        0,
                        2,
                        claimKey,
                        List.of()
                );
        BreedingClaimLimitPolicyService.Decision owner =
                new BreedingClaimLimitPolicyService.Decision(
                        true,
                        true,
                        10,
                        7,
                        0,
                        3,
                        null,
                        List.of(ownerKey),
                        "owner-cap-allow"
                );

        BreedingClaimLimitPolicyService.Decision combined =
                BreedingClaimLimitPolicyService.combineAllowed(claim, owner);

        assertEquals(2, combined.remainingHeadroom());
        assertEquals(claimKey, combined.claimReservationKey());
        assertEquals(List.of(ownerKey), combined.playerReservationKeys());
    }

    private static BreedingClaimLimitPolicyService.ResolvedClaim claimFound(int chunkCount) {
        return new BreedingClaimLimitPolicyService.ResolvedClaim(
                BreedingClaimLimitPolicyService.ClaimResolutionStatus.CLAIM_FOUND,
                new BreedingClaimLimitPolicyService.ClaimReservationKey("world", UUID.randomUUID()),
                chunkCount,
                null
        );
    }

    private static TwGlobalConfig globalSettings(boolean simpleClaimsEnabled,
                                                 int perClaimChunk,
                                                 int perClaimTotal,
                                                 boolean breedingRequiresClaim) throws Exception {
        TwGlobalConfig global = TwGlobalConfig.defaultConfig();
        setField(global, "simpleClaimsEnabled", simpleClaimsEnabled);
        setField(global, "simpleClaimsBreedingLimitPerClaimChunk", perClaimChunk);
        setField(global, "simpleClaimsBreedingLimitPerClaimTotal", perClaimTotal);
        setField(global, "simpleClaimsBreedingRequiresClaim", breedingRequiresClaim);
        return global;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
