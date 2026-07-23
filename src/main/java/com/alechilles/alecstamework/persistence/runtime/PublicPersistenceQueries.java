package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.coop.CoopConflictDiagnostic;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePublicPersistenceAdapter;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
}
