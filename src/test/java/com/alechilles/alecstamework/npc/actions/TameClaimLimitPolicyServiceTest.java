package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests released SimpleClaims tame claim limit behavior. */
class TameClaimLimitPolicyServiceTest {
    @Test
    void disabledSimpleClaimsSkipsClaimCaps() throws Exception {
        TwGlobalConfig global = globalSettings(false, 1, 1, false);
        BreedingClaimLimitPolicyService.Decision decision = TameClaimLimitPolicyService.evaluateResolved(
                global,
                claimFound(1),
                99
        );

        assertTrue(decision.allowed());
        assertEquals("population-caps-disabled", decision.reason());
    }

    @Test
    void simpleClaimsTotalLimitBlocksAtBoundary() throws Exception {
        TwGlobalConfig global = globalSettings(true, 0, 2, false);
        BreedingClaimLimitPolicyService.Decision decision = TameClaimLimitPolicyService.evaluateResolved(
                global,
                claimFound(4),
                2
        );

        assertFalse(decision.allowed());
        assertEquals("claim-cap-reached", decision.reason());
    }

    private static BreedingClaimLimitPolicyService.ResolvedClaim claimFound(int chunkCount) {
        return new BreedingClaimLimitPolicyService.ResolvedClaim(
                BreedingClaimLimitPolicyService.ClaimResolutionStatus.CLAIM_FOUND,
                new BreedingClaimLimitPolicyService.ClaimReservationKey("world", UUID.randomUUID()),
                chunkCount,
                null
        );
    }

    private static TwGlobalConfig globalSettings(boolean enabled,
                                                 int perChunk,
                                                 int total,
                                                 boolean requiresClaim) throws Exception {
        TwGlobalConfig global = TwGlobalConfig.defaultConfig();
        setField(global, "simpleClaimsEnabled", enabled);
        setField(global, "simpleClaimsBreedingLimitPerClaimChunk", perChunk);
        setField(global, "simpleClaimsBreedingLimitPerClaimTotal", total);
        setField(global, "simpleClaimsBreedingRequiresClaim", requiresClaim);
        return global;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
