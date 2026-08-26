package com.alechilles.alecstamework.companion.population.domain;

import javax.annotation.Nonnull;

/** Capacity denial with the exact usage snapshot needed for player feedback. */
public final class PopulationDomainCapacityException extends IllegalStateException {
    private final PopulationDomainAdmission.Status status;
    private final long currentUsage;
    private final long requestedUsage;
    private final int limit;

    public PopulationDomainCapacityException(
            @Nonnull PopulationDomainAdmission.Status status,
            long currentUsage,
            long requestedUsage,
            int limit
    ) {
        super(reason(status));
        if (currentUsage < 0 || requestedUsage <= 0 || limit <= 0) {
            throw new IllegalArgumentException("Valid capacity denial values are required");
        }
        this.status = status;
        this.currentUsage = currentUsage;
        this.requestedUsage = requestedUsage;
        this.limit = limit;
    }

    @Nonnull
    public PopulationDomainAdmission.Status status() {
        return status;
    }

    public long currentUsage() {
        return currentUsage;
    }

    public long requestedUsage() {
        return requestedUsage;
    }

    public int limit() {
        return limit;
    }

    /** Creates the typed denial from the exact transaction count snapshot. */
    @Nonnull
    public static PopulationDomainCapacityException from(
            @Nonnull PopulationDomainAdmission admission
    ) {
        PopulationDomainReservation reservation = admission.reservation();
        PopulationDomainCounts counts = admission.counts();
        return switch (admission.status()) {
            case OWNED_CAPACITY_REACHED -> new PopulationDomainCapacityException(
                    admission.status(),
                    Math.addExact(counts.committedOwned(), counts.pendingOwned()),
                    reservation.weightedOwnedDelta(),
                    reservation.snapshottedMaxOwned()
            );
            case DEPLOYABLE_CAPACITY_REACHED -> new PopulationDomainCapacityException(
                    admission.status(),
                    Math.addExact(
                            counts.committedDeployable(), counts.pendingDeployable()
                    ),
                    reservation.weightedDeployableDelta(),
                    reservation.snapshottedMaxDeployable()
            );
            default -> throw new IllegalArgumentException(
                    "A capacity denial admission is required"
            );
        };
    }

    @Nonnull
    private static String reason(PopulationDomainAdmission.Status status) {
        if (status != PopulationDomainAdmission.Status.OWNED_CAPACITY_REACHED
                && status != PopulationDomainAdmission.Status.DEPLOYABLE_CAPACITY_REACHED) {
            throw new IllegalArgumentException("A capacity denial status is required");
        }
        return "population_domain_" + status.name().toLowerCase();
    }
}
