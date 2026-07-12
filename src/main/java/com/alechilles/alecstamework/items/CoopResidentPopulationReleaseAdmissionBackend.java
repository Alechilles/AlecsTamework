package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.CoopPopulationReleaseAdmissionService;
import com.alechilles.alecstamework.ownership.CoopPopulationReleaseAdmissionService.ReleaseRequest;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.annotation.Nonnull;

/** Typed production bridge from the managed release collaborator to population admission. */
final class CoopResidentPopulationReleaseAdmissionBackend
        implements ManagedCoopReleasePopulationCoordinator.AdmissionBackend {
    private final CoopPopulationReleaseAdmissionService admissions;

    CoopResidentPopulationReleaseAdmissionBackend(
            @Nonnull CoopPopulationReleaseAdmissionService admissions) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
    }

    @Override
    public CompletableFuture<ManagedCoopReleasePopulationCoordinator.BackendPreparation> prepare(
            ReleaseRequest request,
            Function<UUID, String> durableContextFactory) {
        return admissions.prepareAsync(request, durableContextFactory)
                .thenApply(result -> {
                    var prepared = result != null ? result.preparedRelease() : null;
                    return new ManagedCoopReleasePopulationCoordinator.BackendPreparation(
                            result == null
                                    ? ManagedCoopReleasePopulationCoordinator.PreparationStatus.AMBIGUOUS
                                    : switch (result.disposition()) {
                                        case PREPARED -> ManagedCoopReleasePopulationCoordinator.PreparationStatus.PREPARED;
                                        case DEFINITIVE_DENIAL -> ManagedCoopReleasePopulationCoordinator.PreparationStatus.DENIED;
                                        case AMBIGUOUS -> ManagedCoopReleasePopulationCoordinator.PreparationStatus.AMBIGUOUS;
                                    },
                            prepared != null ? prepared.profileId() : null,
                            prepared != null ? prepared.plannedNpcUuid() : null,
                            prepared,
                            result != null ? result.reason()
                                    : "population_release_prepare_result_missing");
                });
    }

    @Override
    public boolean claim(Object handle) {
        return admissions.claimForSpawn(cast(handle));
    }

    @Override
    public boolean writeSpawnHolder(Object handle, Holder<EntityStore> holder) {
        return admissions.writeSpawnHolder(cast(handle), holder).applied();
    }

    @Override
    public CompletableFuture<CompanionPopulationCommitResult> commit(Object handle) {
        return admissions.commitAsync(cast(handle));
    }

    @Override
    public CompletableFuture<Boolean> cancel(Object handle, String reason) {
        return admissions.cancelAsync(cast(handle), reason);
    }

    @Override
    public void markReadinessDegraded(String reason) {
        admissions.markReadinessDegraded(reason);
    }

    private CoopPopulationReleaseAdmissionService.PreparedRelease cast(Object handle) {
        if (!(handle instanceof CoopPopulationReleaseAdmissionService.PreparedRelease prepared)) {
            throw new IllegalArgumentException("invalid population release handle");
        }
        return prepared;
    }
}
