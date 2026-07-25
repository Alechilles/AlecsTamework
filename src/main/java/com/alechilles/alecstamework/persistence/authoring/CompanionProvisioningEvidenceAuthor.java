package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.CompanionProvisioningLinkRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningRequest;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransitionRequest;
import com.alechilles.alecstamework.persistence.facade.ReplacementCompanionProvisioningApi;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Cohesive creation, link, activation, and revival evidence author. */
public final class CompanionProvisioningEvidenceAuthor
        implements ReplacementCompanionProvisioningApi.MutationAuthor {
    private final CompanionProvisioningCreationAuthor creations;
    private final ProvisionedCompanionTransitionAuthor transitions;

    CompanionProvisioningEvidenceAuthor(
            @Nonnull CompanionProvisioningCreationAuthor creations,
            @Nonnull ProvisionedCompanionTransitionAuthor transitions
    ) {
        this.creations = Objects.requireNonNull(creations, "creations");
        this.transitions = Objects.requireNonNull(
                transitions, "transitions"
        );
    }

    @Override
    public CompletionStage<
            ReplacementCompanionProvisioningApi.PreparedProvisioning> prepare(
            CompanionProvisioningRequest request
    ) {
        return creations.prepare(request);
    }

    @Override
    public CompletionStage<
            ReplacementCompanionProvisioningApi.PreparedProvisioning> prepare(
            CompanionProvisioningLinkRequest request
    ) {
        return creations.prepare(request);
    }

    @Override
    public CompletionStage<
            ReplacementCompanionProvisioningApi.PreparedTransition> prepare(
            ProvisionedCompanionTransitionRequest request
    ) {
        return transitions.prepare(request);
    }
}
