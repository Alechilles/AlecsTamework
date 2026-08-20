package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Frozen lifecycle admission evidence carried into one shared operation. */
public record LifecycleAdmissionEvidence(
        @Nonnull Status status,
        @Nullable PopulationDomainAdmissionOperation.Payload payload,
        @Nullable PopulationAdmissionComposition composition,
        @Nullable PopulationDomainConvergencePlan convergencePlan
) {
    /** Source-compatible evidence constructor used by pre-convergence payloads. */
    public LifecycleAdmissionEvidence(
            Status status,
            PopulationDomainAdmissionOperation.Payload payload,
            PopulationAdmissionComposition composition
    ) {
        this(status, payload, composition, null);
    }

    public LifecycleAdmissionEvidence {
        if (status == null) {
            throw new IllegalArgumentException("Lifecycle admission status is required");
        }
        if (status == Status.MANAGED && payload == null) {
            throw new IllegalArgumentException(
                    "Managed lifecycle evidence requires a durable payload"
            );
        }
        if (status != Status.MANAGED && payload != null) {
            throw new IllegalArgumentException(
                    "Only managed lifecycle evidence carries a durable payload"
            );
        }
        if (status != Status.MANAGED && convergencePlan != null) {
            throw new IllegalArgumentException(
                    "Only managed lifecycle evidence carries convergence evidence"
            );
        }
        if (convergencePlan != null && payload != null
                && !convergencePlan.profileId().equals(payload.profileId())) {
            throw new IllegalArgumentException(
                    "Lifecycle convergence evidence must match the managed profile"
            );
        }
    }

    @Nonnull
    public static LifecycleAdmissionEvidence unmanaged() {
        return new LifecycleAdmissionEvidence(Status.UNMANAGED, null, null);
    }

    @Nonnull
    public static LifecycleAdmissionEvidence neutral() {
        return new LifecycleAdmissionEvidence(Status.NEUTRAL, null, null);
    }

    @Nonnull
    public static LifecycleAdmissionEvidence managed(
            @Nonnull PopulationDomainAdmissionOperation.Payload payload,
            @Nullable PopulationAdmissionComposition composition
    ) {
        return new LifecycleAdmissionEvidence(Status.MANAGED, payload, composition);
    }

    /** Creates managed evidence with optional frozen source-row convergence. */
    @Nonnull
    public static LifecycleAdmissionEvidence managed(
            @Nonnull PopulationDomainAdmissionOperation.Payload payload,
            @Nullable PopulationAdmissionComposition composition,
            @Nullable PopulationDomainConvergencePlan convergencePlan
    ) {
        return new LifecycleAdmissionEvidence(
                Status.MANAGED, payload, composition, convergencePlan
        );
    }

    /** Returns the status used by callers that must attach a domain participant. */
    public enum Status {
        UNMANAGED,
        NEUTRAL,
        MANAGED
    }
}
