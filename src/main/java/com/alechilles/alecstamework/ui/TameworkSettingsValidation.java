package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.integration.claims.ClaimProviderRequest;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure validation shared by the settings form parser and its regression tests. */
final class TameworkSettingsValidation {
    private TameworkSettingsValidation() {
    }

    @Nonnull
    static ClaimProviderRequest resolveClaimProvider(@Nullable String submittedValue,
                                                     @Nonnull ClaimProviderRequest fallback) {
        String normalized = submittedValue == null ? "" : submittedValue.trim();
        return normalized.isBlank()
                ? fallback
                : ClaimProviderRequest.fromConfigValue(normalized);
    }
}
