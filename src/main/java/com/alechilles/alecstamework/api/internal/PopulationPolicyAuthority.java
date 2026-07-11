package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import javax.annotation.Nonnull;

/** Injectable runtime authority seam for owner-cap evaluation and mutation-bound admission. */
public interface PopulationPolicyAuthority extends PopulationAdmissionApi {
    @Nonnull
    OwnerPopulationCapDecisionViewV2 evaluateOwnerCap(@Nonnull OwnerPopulationCapRequestV2 request);
}
