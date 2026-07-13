package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.PopulationDetachRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopPopulationMutationContext;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Prepares the exact durable detach required when an item captures a deployed coop companion.
 *
 * <p>The immutable managed-coop index is only admission evidence. SQLite repeats every identity,
 * location, UUID, and generation check inside the population commit transaction.</p>
 */
public final class SpawnerManagedCoopCaptureDetachService {
    public enum PlanStatus {
        NOT_MANAGED,
        DETACH,
        REJECTED
    }

    public record Plan(@Nonnull PlanStatus status,
                       @Nullable String durableContextJson,
                       @Nullable String detail) {
        public Plan {
            Objects.requireNonNull(status, "status");
        }

        public boolean accepted() {
            return status != PlanStatus.REJECTED;
        }

        public boolean requiresDetach() {
            return status == PlanStatus.DETACH;
        }
    }

    private final ManagedCoopResidentIndex residentIndex;
    private final BooleanSupplier trustGate;
    private final Supplier<ManagedCoopCompositeIndexRefreshService.RefreshResult> refresh;
    private final LongSupplier clock;

    public SpawnerManagedCoopCaptureDetachService(
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes) {
        this(
                residentIndex,
                compositeIndexes::isTrusted,
                compositeIndexes::refresh,
                System::currentTimeMillis
        );
    }

    SpawnerManagedCoopCaptureDetachService(
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull BooleanSupplier trustGate,
            @Nonnull Supplier<ManagedCoopCompositeIndexRefreshService.RefreshResult> refresh,
            @Nonnull LongSupplier clock) {
        this.residentIndex = Objects.requireNonNull(residentIndex, "residentIndex");
        this.trustGate = Objects.requireNonNull(trustGate, "trustGate");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Resolves an ordinary capture or an exact deployed-assignment detach without touching SQLite. */
    @Nonnull
    public Plan prepare(@Nonnull UUID sourceNpcUuid) {
        Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        ManagedCoopResidentIndex.Snapshot snapshot = residentIndex.snapshot();
        if (!trustGate.getAsBoolean() || snapshot.revision() == 0L) {
            return rejected("managed_coop_capture_index_unavailable");
        }
        ResidentRecord resident = snapshot.residentByUuid(sourceNpcUuid);
        if (resident == null) {
            return new Plan(PlanStatus.NOT_MANAGED, null, null);
        }
        if (resident.state() != ResidentState.DEPLOYED
                || !sourceNpcUuid.equals(resident.residentUuid())
                || !sourceNpcUuid.equals(resident.deployedNpcUuid())) {
            return rejected("managed_coop_capture_source_not_current_deployed_resident");
        }
        PopulationDetachRequest request = new PopulationDetachRequest(
                resident.residentId(),
                resident.authorityKey(),
                resident.coopId(),
                resident.residentSlot(),
                resident.profileId(),
                sourceNpcUuid,
                resident.generation(),
                clock.getAsLong()
        );
        return new Plan(
                PlanStatus.DETACH,
                ManagedCoopPopulationMutationContext.detachExtensionJson(request),
                null
        );
    }

    /** Republishes the paired resident/operation index after a committed detach. */
    public boolean refreshAfterCommit() {
        try {
            ManagedCoopCompositeIndexRefreshService.RefreshResult result = refresh.get();
            return result != null && result.refreshed();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Nonnull
    private Plan rejected(@Nonnull String detail) {
        return new Plan(PlanStatus.REJECTED, null, detail);
    }
}
