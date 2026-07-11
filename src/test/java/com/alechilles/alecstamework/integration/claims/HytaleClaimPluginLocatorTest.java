package com.alechilles.alecstamework.integration.claims;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HytaleClaimPluginLocatorTest {
    @Test
    void mapsMissingAndInstalledWithoutLiveInstanceSeparately() {
        assertEquals(
                ClaimProviderState.ABSENT,
                HytaleClaimPluginLocator.mapLifecycleState(false, null)
        );
        assertEquals(
                ClaimProviderState.NOT_READY,
                HytaleClaimPluginLocator.mapLifecycleState(true, null)
        );
    }

    @Test
    void requiresExactEnabledStateForReadiness() {
        assertEquals(ClaimProviderState.NOT_READY,
                HytaleClaimPluginLocator.mapLifecycleState(true, PluginState.NONE));
        assertEquals(ClaimProviderState.NOT_READY,
                HytaleClaimPluginLocator.mapLifecycleState(true, PluginState.SETUP));
        assertEquals(ClaimProviderState.NOT_READY,
                HytaleClaimPluginLocator.mapLifecycleState(true, PluginState.START));
        assertEquals(ClaimProviderState.READY,
                HytaleClaimPluginLocator.mapLifecycleState(true, PluginState.ENABLED));
        assertEquals(ClaimProviderState.DISABLED,
                HytaleClaimPluginLocator.mapLifecycleState(true, PluginState.SHUTDOWN));
        assertEquals(ClaimProviderState.DISABLED,
                HytaleClaimPluginLocator.mapLifecycleState(true, PluginState.DISABLED));
        assertEquals(ClaimProviderState.ERROR,
                HytaleClaimPluginLocator.mapLifecycleState(true, PluginState.FAILED));
    }

    @Test
    void questLinesIdentifierUsesVerifiedPluginCoordinates() {
        assertEquals(
                "net.evilcraft:QuestLinesClaims",
                PluginIdentifier.fromString(HytaleClaimPluginLocator.QUESTLINES_PLUGIN_IDENTIFIER).toString()
        );
    }
}
