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
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Narrow canonical read lane used while authoring restored feature evidence.
 *
 * <p>The interface keeps author tests deterministic while the production
 * adapter delegates every read to the replacement persistence authority.</p>
 */
public interface ReplacementFeatureEvidenceQueries {
    @Nonnull
    CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(@Nonnull ProfileId profileId);

    @Nonnull
    CompletionStage<PersistenceReadResult<CommandRoster>>
    findRoster(@Nonnull CommandFamilyKey familyKey);

    @Nonnull
    CompletionStage<PersistenceReadResult<CommandRosterMembership>>
    findMembership(@Nonnull ProfileId profileId);

    @Nonnull
    CompletionStage<PersistenceReadResult<TimedSummonLease>>
    findTimedLease(@Nonnull ProfileId profileId);

    @Nonnull
    CompletionStage<PersistenceReadResult<ProvisioningRecord>>
    findProvisioning(@Nonnull ProfileId profileId);

    @Nonnull
    CompletionStage<PersistenceReadResult<List<PopulationGroupAssignment>>>
    findPopulationAssignments();

    @Nonnull
    CompletionStage<PersistenceReadResult<PublicOperationEvidence>>
    findOperation(
            @Nonnull OperationKind kind,
            @Nonnull IdempotencyKey idempotencyKey
    );
}
