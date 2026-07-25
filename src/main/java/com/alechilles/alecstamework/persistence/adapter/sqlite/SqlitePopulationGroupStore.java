package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAdmission;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPort;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thin connection-bound composition of focused group assignment and admission stores. */
public final class SqlitePopulationGroupStore implements PopulationGroupPort {
    private final SqlitePopulationGroupAssignmentStore assignments;
    private final SqlitePopulationGroupAdmissionStore admissions;

    public SqlitePopulationGroupStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "Population group store connection is required"
            );
        }
        assignments = new SqlitePopulationGroupAssignmentStore(connection);
        admissions = new SqlitePopulationGroupAdmissionStore(connection);
    }

    @Override
    public Optional<PopulationGroupAssignment> findAssignment(
            ProfileId profileId
    ) {
        return assignments.find(profileId);
    }

    @Override
    public List<PopulationGroupAssignment> findAllAssignments() {
        return assignments.findAll();
    }

    @Override
    public List<ProfileId> findStaleProfiles() {
        return assignments.findStaleProfiles();
    }

    @Override
    public PersistenceMutationResult<PopulationGroupAssignment>
    replaceAssignment(
            @Nullable Long expectedAssignmentRevision,
            PopulationGroupAssignment next
    ) {
        return assignments.replace(expectedAssignmentRevision, next);
    }

    @Override
    public PopulationGroupCounts counts(PopulationGroupBucket bucket) {
        return admissions.counts(bucket);
    }

    @Override
    public Optional<PopulationGroupReservation> findReservation(
            OperationId operationId,
            PopulationGroupBucket bucket
    ) {
        return admissions.find(operationId, bucket);
    }

    @Override
    public List<PopulationGroupReservation> findReservations(
            OperationId operationId
    ) {
        return admissions.findByOperation(operationId);
    }

    @Override
    public PopulationGroupAdmission reserve(
            PopulationGroupReservation reservation
    ) {
        return admissions.reserve(reservation);
    }

    @Override
    public boolean retireExact(
            OperationId operationId,
            int expectedReservationCount
    ) {
        return admissions.retireExact(
                operationId, expectedReservationCount
        );
    }
}

