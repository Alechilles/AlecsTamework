package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Production evidence-query adapter over the replacement public read lane. */
public final class PublicPersistenceFeatureEvidenceQueries
        implements ReplacementFeatureEvidenceQueries {
    private final PublicPersistenceQueries queries;

    public PublicPersistenceFeatureEvidenceQueries(
            @Nonnull PublicPersistenceQueries queries
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    @Override
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(ProfileId profileId) {
        return queries.findProfile(profileId);
    }

    @Override
    public CompletionStage<PersistenceReadResult<CommandRoster>> findRoster(
            CommandFamilyKey familyKey
    ) {
        return queries.findCommandRoster(familyKey);
    }

    @Override
    public CompletionStage<PersistenceReadResult<CommandRosterMembership>>
    findMembership(ProfileId profileId) {
        return queries.findCommandRosterMembership(profileId);
    }

    @Override
    public CompletionStage<PersistenceReadResult<TimedSummonLease>>
    findTimedLease(ProfileId profileId) {
        return queries.findTimedSummonLease(profileId);
    }

    @Override
    public CompletionStage<PersistenceReadResult<ProvisioningRecord>>
    findProvisioning(ProfileId profileId) {
        return queries.findProvisioning(profileId);
    }

    @Override
    public CompletionStage<PersistenceReadResult<List<
            PopulationGroupAssignment>>> findPopulationAssignments() {
        return queries.findAllPopulationGroupAssignments();
    }

    @Override
    public CompletionStage<PersistenceReadResult<PublicOperationEvidence>>
    findOperation(OperationKind kind, IdempotencyKey idempotencyKey) {
        return queries.findOperation(kind, idempotencyKey);
    }
}
