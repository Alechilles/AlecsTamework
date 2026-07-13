package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;

@FunctionalInterface
interface ManagedCoopCurrentReleaseResident {
    @Nonnull
    ResidentRecord resolve(@Nonnull SpawnReady claim, @Nonnull ResidentRecord selected);
}

/** Resolves the committed RELEASING resident published by the release-claim index refresh. */
final class ManagedCoopReleaseResidentResolver implements ManagedCoopCurrentReleaseResident {
    private final ManagedCoopResidentIndex residents;
    private final BooleanSupplier compositeTrust;

    ManagedCoopReleaseResidentResolver(
            @Nonnull ManagedCoopResidentIndex residents,
            @Nonnull BooleanSupplier compositeTrust
    ) {
        this.residents = Objects.requireNonNull(residents, "residents");
        this.compositeTrust = Objects.requireNonNull(compositeTrust, "compositeTrust");
    }

    @Nonnull
    ResidentRecord resolve(@Nonnull SpawnReady claim) {
        Objects.requireNonNull(claim, "claim");
        if (!trusted()) {
            throw new IllegalStateException("managed_coop_release_resident_index_untrusted");
        }
        ManagedCoopResidentIndex.Snapshot snapshot = residents.snapshot();
        if (snapshot.revision() < claim.indexRevision()) {
            throw new IllegalStateException("managed_coop_release_resident_index_stale");
        }
        ResidentRecord current = snapshot.residentByProfile(claim.profileId());
        if (!matches(claim, current)) {
            throw new IllegalStateException("managed_coop_release_current_resident_mismatch");
        }
        if (!trusted() || residents.snapshot().revision() != snapshot.revision()) {
            throw new IllegalStateException("managed_coop_release_resident_index_changed");
        }
        return current;
    }

    @Override
    public ResidentRecord resolve(SpawnReady claim, ResidentRecord selected) {
        return resolve(claim);
    }

    private boolean trusted() {
        try {
            return compositeTrust.getAsBoolean() && residents.isTrusted();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean matches(SpawnReady claim, ResidentRecord resident) {
        return resident != null
                && resident.active()
                && resident.state() == ResidentState.RELEASING
                && resident.generation() == claim.releasingResidentGeneration()
                && resident.residentId().equals(claim.residentId())
                && resident.profileId().equals(claim.profileId())
                && resident.authorityKey().equals(claim.authorityKey())
                && resident.coopId().equalsIgnoreCase(claim.coopId())
                && resident.residentSlot() == claim.residentSlot()
                && resident.residentUuid().equals(claim.sourceNpcUuid())
                && Objects.equals(resident.sourceNpcUuid(), claim.sourceNpcUuid())
                && resident.deployedNpcUuid() == null
                && resident.snapshotHash().equals(claim.snapshotHash());
    }
}
