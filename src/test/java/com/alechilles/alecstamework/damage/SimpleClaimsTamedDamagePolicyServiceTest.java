package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleClaimsTamedDamagePolicyServiceTest {

    @Test
    void evaluateSkipsWhenSimpleClaimsDamageProtectionIsDisabled() {
        TwGlobalConfig globalConfig = TwGlobalConfig.defaultConfig();
        SimpleClaimsTamedDamagePolicyService service = new SimpleClaimsTamedDamagePolicyService();

        SimpleClaimsTamedDamagePolicyService.Decision decision = service.evaluate(
                null,
                null,
                null,
                UUID.randomUUID(),
                globalConfig
        );

        assertTrue(decision.allowed());
        assertEquals(SimpleClaimsTamedDamagePolicyService.DecisionStatus.ALLOW_SKIPPED, decision.status());
    }

    @Test
    void evaluateSkipsWhenAttackerCannotBeAttributed() throws Exception {
        TwGlobalConfig globalConfig = enabledGlobalConfig();
        SimpleClaimsTamedDamagePolicyService service = new SimpleClaimsTamedDamagePolicyService();

        SimpleClaimsTamedDamagePolicyService.Decision decision = service.evaluate(
                null,
                null,
                null,
                null,
                globalConfig
        );

        assertTrue(decision.allowed());
        assertEquals(SimpleClaimsTamedDamagePolicyService.DecisionStatus.ALLOW_SKIPPED, decision.status());
    }

    @Test
    void evaluateResolvedDeniesWhenBridgeDeniesAccess() {
        SimpleClaimsBreedingBridge.DamageAccessResult result = new SimpleClaimsBreedingBridge.DamageAccessResult(
                SimpleClaimsBreedingBridge.DamageAccessStatus.DENIED,
                UUID.randomUUID(),
                "attacker-not-allowed"
        );

        SimpleClaimsTamedDamagePolicyService.Decision decision =
                SimpleClaimsTamedDamagePolicyService.evaluateResolved(result);

        assertFalse(decision.allowed());
        assertEquals(SimpleClaimsTamedDamagePolicyService.DecisionStatus.DENY, decision.status());
    }

    @Test
    void evaluateResolvedAllowsWhenBridgeAllowsAccess() {
        SimpleClaimsBreedingBridge.DamageAccessResult result = new SimpleClaimsBreedingBridge.DamageAccessResult(
                SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED,
                UUID.randomUUID(),
                null
        );

        SimpleClaimsTamedDamagePolicyService.Decision decision =
                SimpleClaimsTamedDamagePolicyService.evaluateResolved(result);

        assertTrue(decision.allowed());
        assertEquals(SimpleClaimsTamedDamagePolicyService.DecisionStatus.ALLOW_ENFORCED, decision.status());
    }

    @Test
    void evaluateResolvedIsFailOpenOnLookupErrors() {
        SimpleClaimsBreedingBridge.DamageAccessResult lookupError = new SimpleClaimsBreedingBridge.DamageAccessResult(
                SimpleClaimsBreedingBridge.DamageAccessStatus.LOOKUP_ERROR,
                UUID.randomUUID(),
                "lookup-error"
        );
        SimpleClaimsBreedingBridge.DamageAccessResult unavailable = new SimpleClaimsBreedingBridge.DamageAccessResult(
                SimpleClaimsBreedingBridge.DamageAccessStatus.UNAVAILABLE,
                null,
                "unavailable"
        );

        SimpleClaimsTamedDamagePolicyService.Decision lookupDecision =
                SimpleClaimsTamedDamagePolicyService.evaluateResolved(lookupError);
        SimpleClaimsTamedDamagePolicyService.Decision unavailableDecision =
                SimpleClaimsTamedDamagePolicyService.evaluateResolved(unavailable);

        assertTrue(lookupDecision.allowed());
        assertTrue(unavailableDecision.allowed());
        assertEquals(SimpleClaimsTamedDamagePolicyService.DecisionStatus.ALLOW_FAIL_OPEN, lookupDecision.status());
        assertEquals(SimpleClaimsTamedDamagePolicyService.DecisionStatus.ALLOW_FAIL_OPEN, unavailableDecision.status());
    }

    private static TwGlobalConfig enabledGlobalConfig() throws Exception {
        TwGlobalConfig globalConfig = TwGlobalConfig.defaultConfig();
        setField(globalConfig, "simpleClaimsEnabled", true);
        setField(globalConfig, "simpleClaimsDamageProtectTamedFromNonMembers", true);
        return globalConfig;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
