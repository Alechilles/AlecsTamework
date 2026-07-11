package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Fail-closed authority used by compatibility constructors until the coordinator is wired. */
public final class UnavailablePopulationPolicyAuthority implements PopulationPolicyAuthority {
    public static final UnavailablePopulationPolicyAuthority INSTANCE = new UnavailablePopulationPolicyAuthority();
    public static final String REASON = "population-admission-authority-unavailable";

    private final PopulationAdmissionApi unavailableAdmissions = PopulationAdmissionApi.unavailable();

    private UnavailablePopulationPolicyAuthority() {
    }

    @Nonnull
    @Override
    public OwnerPopulationCapDecisionViewV2 evaluateOwnerCap(@Nonnull OwnerPopulationCapRequestV2 request) {
        Objects.requireNonNull(request, "request");
        return OwnerPopulationCapDecisionViewV2.unavailable(
                request,
                OwnerPopulationCapDecisionViewV2.Scope.UNKNOWN,
                REASON
        );
    }

    @Nonnull
    @Override
    public PopulationAdmissionDecision tryAdmit(@Nonnull PopulationAdmissionRequest request) {
        return unavailableAdmissions.tryAdmit(request);
    }

    @Nonnull
    @Override
    public PopulationAdmissionDecision claimForApply(@Nonnull PopulationAdmissionToken token) {
        return unavailableAdmissions.claimForApply(token);
    }

    @Nonnull
    @Override
    public PopulationAdmissionDecision commit(@Nonnull PopulationAdmissionToken token) {
        return unavailableAdmissions.commit(token);
    }

    @Nonnull
    @Override
    public PopulationAdmissionDecision cancel(@Nonnull PopulationAdmissionToken token) {
        return unavailableAdmissions.cancel(token);
    }
}
