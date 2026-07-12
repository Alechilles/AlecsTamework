package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationCapDecisionView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Focused public-policy adapter kept out of the already-large API implementation. */
final class PopulationPolicyApiDelegate {
    private final PopulationPolicyAuthority authority;

    PopulationPolicyApiDelegate(@Nonnull PopulationPolicyAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    @Nonnull
    PopulationCapDecisionView evaluateLegacy(@Nullable UUID ownerUuid) {
        return authority.evaluateLegacyOwnerCap(ownerUuid);
    }

    @Nonnull
    OwnerPopulationCapDecisionViewV2 evaluate(@Nonnull OwnerPopulationCapRequestV2 request) {
        return authority.evaluateOwnerCap(Objects.requireNonNull(request, "request"));
    }

    @Nonnull
    PopulationAdmissionApi admissions() {
        return authority;
    }

    @Nonnull
    PopulationDiagnosticsView diagnostics() {
        return authority.populationDiagnostics();
    }
}
