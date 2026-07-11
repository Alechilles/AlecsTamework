package com.alechilles.alecstamework.integration.claims;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimProviderRequestTest {
    @Test
    void acceptsDocumentedProviderAliases() {
        Map<String, ClaimIntegrationProvider> aliases = new LinkedHashMap<>();
        aliases.put("simpleclaim", ClaimIntegrationProvider.SIMPLE_CLAIMS);
        aliases.put("Simple Claims", ClaimIntegrationProvider.SIMPLE_CLAIMS);
        aliases.put("qlclaims", ClaimIntegrationProvider.QUESTLINES_CLAIMS);
        aliases.put("qlc", ClaimIntegrationProvider.QUESTLINES_CLAIMS);
        aliases.put("questline_claims", ClaimIntegrationProvider.QUESTLINES_CLAIMS);
        aliases.put("disabled", ClaimIntegrationProvider.OFF);
        aliases.put("none", ClaimIntegrationProvider.OFF);
        aliases.put("false", ClaimIntegrationProvider.OFF);
        aliases.put("automatic", ClaimIntegrationProvider.AUTO);

        for (Map.Entry<String, ClaimIntegrationProvider> alias : aliases.entrySet()) {
            ClaimProviderRequest request = ClaimProviderRequest.fromConfigValue(alias.getKey());
            assertTrue(request.valid(), alias.getKey());
            assertEquals(alias.getValue(), request.provider(), alias.getKey());
        }
    }

    @Test
    void missingValueUsesAutoButInvalidExplicitValueIsPreserved() {
        ClaimProviderRequest missing = ClaimProviderRequest.fromConfigValue("  ");
        ClaimProviderRequest invalid = ClaimProviderRequest.fromConfigValue("TownyMaybe");

        assertTrue(missing.valid());
        assertEquals(ClaimIntegrationProvider.AUTO, missing.provider());
        assertFalse(invalid.valid());
        assertNull(invalid.provider());
        assertEquals("TownyMaybe", invalid.configuredValue());
    }

    @Test
    void legacyParserRemainsBackwardCompatibleWhileStrictParserExposesInvalidity() {
        assertEquals(ClaimIntegrationProvider.AUTO, ClaimIntegrationProvider.fromConfigValue("unknown"));
        assertNull(ClaimIntegrationProvider.tryFromConfigValue("unknown"));
    }
}
