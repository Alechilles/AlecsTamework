package com.alechilles.alecstamework.integration.claims;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimIntegrationProviderSelectorTest {
    @Test
    void unavailableBridgePreservesOperationDiagnostic() {
        ClaimIntegrationBridge bridge = ClaimIntegrationProviderSelector.unavailable(
                "questlines-claims",
                "owner runtime is unavailable"
        );

        assertFalse(bridge.isAvailable());
        assertEquals("questlines-claims", bridge.providerId());
        assertEquals("owner runtime is unavailable", bridge.getUnavailableReason());
        assertEquals(ClaimLookupResult.Status.UNAVAILABLE, bridge.lookupClaim("world", 0, 0).status());
    }

    @Test
    void unavailableBridgeNormalizesBlankInputs() {
        ClaimIntegrationBridge bridge = ClaimIntegrationProviderSelector.unavailable(" ", null);

        assertEquals("unavailable", bridge.providerId());
        assertEquals("Claim integration provider is unavailable.", bridge.getUnavailableReason());
    }

    @Test
    void fromConfigValueAcceptsAliases() {
        assertEquals(ClaimIntegrationProvider.AUTO, ClaimIntegrationProvider.fromConfigValue(null));
        assertEquals(ClaimIntegrationProvider.AUTO, ClaimIntegrationProvider.fromConfigValue(""));
        assertEquals(ClaimIntegrationProvider.SIMPLE_CLAIMS, ClaimIntegrationProvider.fromConfigValue("SimpleClaims"));
        assertEquals(ClaimIntegrationProvider.QUESTLINES_CLAIMS, ClaimIntegrationProvider.fromConfigValue("QuestLinesClaims"));
        assertEquals(ClaimIntegrationProvider.OFF, ClaimIntegrationProvider.fromConfigValue("disabled"));
        assertTrue(ClaimIntegrationProvider.AUTO.configValue().equals("Auto"));
    }
}
