package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests provider-neutral tame claim limit policy behavior. */
class TameClaimLimitPolicyServiceTest {
    @Test
    void offProviderSkipsClaimCaps() throws Exception {
        TwGlobalConfig global = globalSettings("Off", true, 1, 1, false);
        BreedingClaimLimitPolicyService.Decision decision = TameClaimLimitPolicyService.evaluateResolved(
                global,
                claimFound("questlines-claims", 1),
                99
        );

        assertTrue(decision.allowed());
        assertEquals("population-caps-disabled", decision.reason());
    }

    @Test
    void questLinesProviderUsesProviderSpecificClaimKey() throws Exception {
        TwGlobalConfig global = globalSettings("QuestLinesClaims", true, 0, 2, false);
        BreedingClaimLimitPolicyService.Decision decision = TameClaimLimitPolicyService.evaluateResolved(
                global,
                claimFound("questlines-claims", 4),
                2
        );

        assertFalse(decision.allowed());
        assertEquals("claim-cap-reached", decision.reason());
        assertEquals("questlines-claims", decision.claimReservationKey().providerId());
    }

    private static BreedingClaimLimitPolicyService.ResolvedClaim claimFound(String providerId, int chunkCount) {
        ClaimPopulationKey key = new ClaimPopulationKey(
                providerId,
                "world",
                "PLAYER",
                UUID.randomUUID(),
                "42"
        );
        return new BreedingClaimLimitPolicyService.ResolvedClaim(
                BreedingClaimLimitPolicyService.ClaimResolutionStatus.CLAIM_FOUND,
                BreedingClaimLimitPolicyService.ClaimReservationKey.fromPopulationKey(key),
                chunkCount,
                null
        );
    }

    private static TwGlobalConfig globalSettings(String provider,
                                                 boolean enabled,
                                                 int perChunk,
                                                 int total,
                                                 boolean requiresClaim) throws Exception {
        TwGlobalConfig global = TwGlobalConfig.defaultConfig();
        setField(global, "simpleClaimsProvider", ClaimIntegrationProvider.fromConfigValue(provider));
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
