package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationCapDecisionView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Fail-closed authority used by compatibility constructors until the coordinator is wired. */
public final class UnavailablePopulationPolicyAuthority implements PopulationPolicyAuthority {
    public static final UnavailablePopulationPolicyAuthority INSTANCE = new UnavailablePopulationPolicyAuthority();
    public static final String REASON = "population-admission-authority-unavailable";

    private final PopulationAdmissionApi unavailableAdmissions = PopulationAdmissionApi.unavailable();

    private UnavailablePopulationPolicyAuthority() {
    }

    @Nonnull
    @Override
    public PopulationCapDecisionView evaluateLegacyOwnerCap(@Nullable UUID ownerUuid) {
        return new PopulationCapDecisionView(
                ownerUuid,
                ownerUuid == null,
                ownerUuid != null,
                0,
                ownerUuid == null ? 0 : -1,
                ownerUuid == null ? Integer.MAX_VALUE : 0,
                null,
                ownerUuid == null ? "owner-cap-no-owner" : REASON
        );
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
    public PopulationDiagnosticsView populationDiagnostics() {
        return PopulationDiagnosticsView.unavailable();
    }

    @Nonnull
    @Override
    public CompletionStage<PopulationAdmissionDecision> tryAdmit(@Nonnull PopulationAdmissionRequest request) {
        return unavailableAdmissions.tryAdmit(request);
    }

    @Nonnull
    @Override
    public CompletionStage<PopulationBatchAdmissionDecision> tryAdmitBatch(
            @Nonnull PopulationBatchAdmissionRequest request
    ) {
        return unavailableAdmissions.tryAdmitBatch(request);
    }

    @Nonnull
    @Override
    public PopulationAdmissionDecision claimForApply(@Nonnull PopulationAdmissionToken token) {
        return unavailableAdmissions.claimForApply(token);
    }

    @Nonnull
    @Override
    public CompletionStage<PopulationAdmissionDecision> commit(@Nonnull PopulationAdmissionToken token) {
        return unavailableAdmissions.commit(token);
    }

    @Nonnull
    @Override
    public CompletionStage<PopulationAdmissionDecision> cancel(@Nonnull PopulationAdmissionToken token) {
        return unavailableAdmissions.cancel(token);
    }

    @Nonnull
    @Override
    public CompletionStage<Integer> cleanupExpired() {
        return unavailableAdmissions.cleanupExpired();
    }
}
