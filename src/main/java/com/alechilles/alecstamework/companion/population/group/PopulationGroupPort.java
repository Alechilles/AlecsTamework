package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Transaction-local authority for group assignment detail and shared reservations. */
public interface PopulationGroupPort {
    @Nonnull
    Optional<PopulationGroupAssignment> findAssignment(
            @Nonnull ProfileId profileId
    );

    @Nonnull
    List<PopulationGroupAssignment> findAllAssignments();

    /** Returns assignments whose source role/revisions cannot match canonical rows. */
    @Nonnull
    List<ProfileId> findStaleProfiles();

    @Nonnull
    PersistenceMutationResult<PopulationGroupAssignment> replaceAssignment(
            @Nullable Long expectedAssignmentRevision,
            @Nonnull PopulationGroupAssignment next
    );

    @Nonnull
    PopulationGroupCounts counts(@Nonnull PopulationGroupBucket bucket);

    @Nonnull
    Optional<PopulationGroupReservation> findReservation(
            @Nonnull OperationId operationId,
            @Nonnull PopulationGroupBucket bucket
    );

    @Nonnull
    List<PopulationGroupReservation> findReservations(
            @Nonnull OperationId operationId
    );

    @Nonnull
    PopulationGroupAdmission reserve(
            @Nonnull PopulationGroupReservation reservation
    );

    boolean retireExact(
            @Nonnull OperationId operationId,
            int expectedReservationCount
    );
}
