package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwGlobalConfigClaimProviderDecodeTest {
    @Test
    void assetDecodePreservesInvalidLegacyProviderRequest() {
        TwGlobalConfig config = decode("TownyMaybe");

        assertFalse(config.getSimpleClaimsProviderRequest().valid());
        assertEquals("TownyMaybe", config.getSimpleClaimsProviderRequest().displayValue());
    }

    @Test
    void assetDecodeRetainsAcceptedAliasesAndBlankAutoDefault() {
        TwGlobalConfig alias = decode("qlc");
        TwGlobalConfig blank = decode("  ");

        assertTrue(alias.getSimpleClaimsProviderRequest().valid());
        assertEquals(ClaimIntegrationProvider.QUESTLINES_CLAIMS, alias.getSimpleClaimsProviderRequest().provider());
        assertTrue(blank.getSimpleClaimsProviderRequest().valid());
        assertEquals(ClaimIntegrationProvider.AUTO, blank.getSimpleClaimsProviderRequest().provider());
    }

    private static TwGlobalConfig decode(String provider) {
        String escaped = provider.replace("\\", "\\\\").replace("\"", "\\\"");
        return TwGlobalConfig.CODEC.decode(
                BsonDocument.parse("{\"SimpleClaims\":{\"Provider\":\"" + escaped + "\"}}"),
                new ExtraInfo()
        );
    }
}
