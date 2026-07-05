package com.alechilles.alecstamework.integration.claims;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimIntegrationProviderSelectorTest {
    @Test
    void autoPrefersQuestLinesWhenBothAreAvailable() {
        FakeBridge questLines = new FakeBridge("questlines-claims", true);
        FakeBridge simpleClaims = new FakeBridge("simpleclaims", true);

        ClaimIntegrationBridge selected = ClaimIntegrationProviderSelector.select(
                ClaimIntegrationProvider.AUTO,
                questLines,
                simpleClaims
        );

        assertSame(questLines, selected);
    }

    @Test
    void autoFallsBackToSimpleClaimsWhenQuestLinesIsUnavailable() {
        FakeBridge questLines = new FakeBridge("questlines-claims", false);
        FakeBridge simpleClaims = new FakeBridge("simpleclaims", true);

        ClaimIntegrationBridge selected = ClaimIntegrationProviderSelector.select(
                ClaimIntegrationProvider.AUTO,
                questLines,
                simpleClaims
        );

        assertSame(simpleClaims, selected);
    }

    @Test
    void explicitOffReturnsUnavailableBridge() {
        ClaimIntegrationBridge selected = ClaimIntegrationProviderSelector.select(
                ClaimIntegrationProvider.OFF,
                new FakeBridge("questlines-claims", true),
                new FakeBridge("simpleclaims", true)
        );

        assertFalse(selected.isAvailable());
        assertEquals("off", selected.providerId());
        assertEquals(ClaimLookupResult.Status.UNAVAILABLE, selected.lookupClaim("world", 0, 0).status());
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

    private record FakeBridge(String providerId, boolean available) implements ClaimIntegrationBridge {
        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String getUnavailableReason() {
            return available ? null : "missing";
        }

        @Override
        public ClaimLookupResult lookupClaim(String worldName, double blockX, double blockZ) {
            return available
                    ? ClaimLookupResult.noClaim()
                    : ClaimLookupResult.unavailable("missing");
        }
    }
}
