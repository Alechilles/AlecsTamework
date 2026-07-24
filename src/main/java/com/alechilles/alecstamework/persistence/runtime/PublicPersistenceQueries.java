package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.coop.CoopConflictDiagnostic;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionProjectionValue;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePublicPersistenceAdapter;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Adapter-neutral canonical reads and rebuildable coop projection lookups. */
public final class PublicPersistenceQueries {
    private final SqlitePublicPersistenceAdapter adapter;

    PublicPersistenceQueries(SqlitePublicPersistenceAdapter adapter) {
        this.adapter = adapter;
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(@Nonnull ProfileId profileId) {
        return adapter.profileReader().findByProfile(profileId);
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(@Nonnull NpcAlias alias) {
        return adapter.profileReader().findByAlias(alias);
    }

    @Nonnull
    public Optional<CompanionProfileProjectionState> projectedProfile(
            @Nonnull ProfileId profileId
    ) {
        return adapter.profileIndex().find(profileId);
    }

    @Nonnull
    public Optional<CompanionProfileProjectionState> projectedProfile(
            @Nonnull NpcAlias alias
    ) {
        return adapter.profileIndex().find(alias);
    }

    @Nonnull
    public Map<ProfileId, CompanionProfileProjectionState>
    projectedProfileSnapshot() {
        return adapter.profileIndex().snapshot();
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CoopSlot>> findCoopSlot(
            @Nonnull CoopSlotKey slotKey
    ) {
        return adapter.coopReader().findSlot(slotKey);
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CoopResidency>>
    findCoopResidency(@Nonnull ProfileId profileId) {
        return adapter.coopReader().findResidencyByProfile(profileId);
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CoopConflictDiagnostic>>
    diagnoseCoopCapture(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId
    ) {
        return adapter.coopReader().diagnoseCapture(slotKey, profileId);
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CoopConflictDiagnostic>>
    diagnoseCoopRelease(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId
    ) {
        return adapter.coopReader().diagnoseRelease(slotKey, profileId);
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<ProfileExtensionData>>
    findExtension(@Nonnull ProfileExtensionKey key) {
        return adapter.extensionReader().findActive(key);
    }

    @Nonnull
    public Optional<ProfileExtensionProjectionValue> projectedExtension(
            @Nonnull ProfileExtensionKey key
    ) {
        return adapter.extensionIndex().find(key);
    }

    @Nonnull
    public Map<String, ProfileExtensionProjectionValue> projectedExtensions(
            @Nonnull ProfileId profileId,
            @Nonnull String namespace
    ) {
        return adapter.extensionIndex().namespace(profileId, namespace);
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<List<ProfileExtensionData>>>
    findExtensions(
            @Nonnull ProfileId profileId,
            @Nonnull String namespace
    ) {
        return adapter.extensionReader()
                .findNamespace(profileId, namespace);
    }

    @Nonnull
    public Optional<CoopOccupancy> projectedCoopResidency(
            @Nonnull ProfileId profileId
    ) {
        return adapter.coopIndex().findByProfile(profileId);
    }

    @Nonnull
    public Map<CoopSlotKey, CoopOccupancy> projectedCoopSnapshot() {
        return adapter.coopIndex().snapshot();
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<List<CompanionLifecycle>>>
    findAllLifecycles() {
        return adapter.lifecycleReader().findAll();
    }

    public long projectedOwnerPopulationCount(
            @Nonnull OwnerPopulationScope scope
    ) {
        return adapter.ownerPopulationIndex().count(scope);
    }

    @Nonnull
    public Map<ProfileId, CompanionLifecycle>
    projectedOwnerPopulationSnapshot() {
        return adapter.ownerPopulationIndex().snapshot();
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<
            List<PopulationGroupAssignment>>>
    findAllPopulationGroupAssignments() {
        return adapter.populationGroupReader().findAllAssignments();
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<List<ProfileId>>>
    findStalePopulationGroupProfiles() {
        return adapter.populationGroupReader().findStaleProfiles();
    }

    @Nonnull
    public PopulationGroupCounts projectedPopulationGroupCounts(
            @Nonnull PopulationGroupBucket bucket
    ) {
        return adapter.populationGroupIndex().counts(bucket);
    }

    @Nonnull
    public Set<ProfileId> projectedLaggingPopulationGroupProfiles() {
        return adapter.populationGroupIndex().laggingProfiles();
    }

    @Nonnull
    public Map<ProfileId, PopulationGroupAssignment>
    projectedPopulationGroupAssignments() {
        return adapter.populationGroupIndex().assignmentSnapshot();
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<PublicOperationEvidence>>
    findOperation(@Nonnull OperationId operationId) {
        return adapter.operationReader().find(operationId)
                .thenApply(this::operationEvidence);
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<PublicOperationEvidence>>
    findOperation(
            @Nonnull OperationKind kind,
            @Nonnull IdempotencyKey idempotencyKey
    ) {
        return adapter.operationReader()
                .findByIdempotency(kind, idempotencyKey)
                .thenApply(this::operationEvidence);
    }

    private PersistenceReadResult<PublicOperationEvidence>
    operationEvidence(
            PersistenceReadResult<com.alechilles.alecstamework.persistence
                    .adapter.sqlite.SqliteOperationReader
                    .OperationReadModel> read
    ) {
        if (read instanceof PersistenceReadResult.Found<
                com.alechilles.alecstamework.persistence.adapter.sqlite
                        .SqliteOperationReader.OperationReadModel> found) {
            return PersistenceReadResult.found(
                    new PublicOperationEvidence(
                            found.value().operation(),
                            found.value().events()
                    ),
                    found.revision()
            );
        }
        if (read instanceof PersistenceReadResult.Failed<?> failed) {
            return PersistenceReadResult.failed(failed.failure());
        }
        return PersistenceReadResult.absent();
    }
}
