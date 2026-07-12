package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationCapDecisionView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Injectable runtime authority seam for owner-cap evaluation and mutation-bound admission. */
public interface PopulationPolicyAuthority extends PopulationAdmissionApi {
    /** Compatibility evaluation for callers that cannot supply world context. */
    @Nonnull
    PopulationCapDecisionView evaluateLegacyOwnerCap(@Nullable UUID ownerUuid);

    @Nonnull
    OwnerPopulationCapDecisionViewV2 evaluateOwnerCap(@Nonnull OwnerPopulationCapRequestV2 request);

    @Nonnull
    PopulationDiagnosticsView populationDiagnostics();
}
