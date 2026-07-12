package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsValidationTest {
    @Test
    void blankSubmissionKeepsCurrentProviderAndAliasesRemainAccepted() {
        ClaimProviderRequest blank = TameworkSettingsValidation.resolveClaimProvider(
                "  ",
                ClaimProviderRequest.forProvider(ClaimIntegrationProvider.QUESTLINES_CLAIMS)
        );
        ClaimProviderRequest alias = TameworkSettingsValidation.resolveClaimProvider(
                "simpleclaim",
                ClaimProviderRequest.forProvider(ClaimIntegrationProvider.AUTO)
        );

        assertTrue(blank.valid());
        assertEquals(ClaimIntegrationProvider.QUESTLINES_CLAIMS, blank.provider());
        assertTrue(alias.valid());
        assertEquals(ClaimIntegrationProvider.SIMPLE_CLAIMS, alias.provider());
    }

    @Test
    void unknownNonblankSubmissionIsRejectedInsteadOfBecomingAuto() {
        ClaimProviderRequest request = TameworkSettingsValidation.resolveClaimProvider(
                "TownyMaybe",
                ClaimProviderRequest.forProvider(ClaimIntegrationProvider.AUTO)
        );

        assertFalse(request.valid());
        assertEquals("TownyMaybe", request.displayValue());
    }

    @Test
    void blankSubmissionDoesNotHideAnInvalidLegacyFallback() {
        ClaimProviderRequest invalid = ClaimProviderRequest.fromConfigValue("TownyMaybe");

        ClaimProviderRequest resolved = TameworkSettingsValidation.resolveClaimProvider(" ", invalid);

        assertFalse(resolved.valid());
        assertEquals("TownyMaybe", resolved.displayValue());
    }
}
