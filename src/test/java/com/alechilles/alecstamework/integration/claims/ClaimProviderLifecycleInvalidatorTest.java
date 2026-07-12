package com.alechilles.alecstamework.integration.claims;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClaimProviderLifecycleInvalidatorTest {
    @Test
    void exactOptionalPluginIdentifiersInvalidateOnlyTheirProvider() {
        List<ClaimIntegrationProvider> invalidated = new ArrayList<>();
        ClaimProviderLifecycleInvalidator listener =
                new ClaimProviderLifecycleInvalidator(invalidated::add);

        listener.onPluginSetupIdentifier(HytaleClaimPluginLocator.QUESTLINES_PLUGIN_IDENTIFIER);
        listener.onPluginSetupIdentifier(HytaleClaimPluginLocator.SIMPLE_CLAIMS_PLUGIN_IDENTIFIER);
        listener.onPluginSetupIdentifier("unrelated:Plugin");
        listener.onPluginSetupIdentifier("buuz135:SimpleClaims");
        listener.onPluginSetupIdentifier(null);

        assertEquals(
                List.of(
                        ClaimIntegrationProvider.QUESTLINES_CLAIMS,
                        ClaimIntegrationProvider.SIMPLE_CLAIMS
                ),
                invalidated
        );
    }

    @Test
    void providerMappingIgnoresUnrelatedAndMissingIdentifiers() {
        assertEquals(
                ClaimIntegrationProvider.QUESTLINES_CLAIMS,
                ClaimProviderLifecycleInvalidator.providerFor(
                        HytaleClaimPluginLocator.QUESTLINES_PLUGIN_IDENTIFIER
                )
        );
        assertEquals(
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                ClaimProviderLifecycleInvalidator.providerFor(
                        HytaleClaimPluginLocator.SIMPLE_CLAIMS_PLUGIN_IDENTIFIER
                )
        );
        assertNull(ClaimProviderLifecycleInvalidator.providerFor("other:Plugin"));
        assertNull(ClaimProviderLifecycleInvalidator.providerFor(null));
    }
}
