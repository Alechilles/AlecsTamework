package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import javax.annotation.Nullable;

/** Frozen owner and family-group evidence for atomic managed admission preparation. */
public record PopulationAdmissionComposition(
        @Nullable OwnerPopulationAdmissionPlan ownerPlan,
        @Nullable PopulationGroupTransitionAdmissionRequest groupRequest
) {
}
