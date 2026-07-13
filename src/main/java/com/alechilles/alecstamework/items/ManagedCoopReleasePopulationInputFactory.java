package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CoopPopulationReleaseAdmissionService.ReleaseRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.PopulationReleaseCommitRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Validates release snapshot evidence and builds the exact population/durable input pair. */
final class ManagedCoopReleasePopulationInputFactory {
    record Input(@Nonnull ReleaseRequest request,
                 @Nonnull PopulationReleaseCommitRequest durableRequest) {
    }

    private final CoopResidentStateSnapshotCodec snapshotCodec;
    private final LongSupplier clock;

    ManagedCoopReleasePopulationInputFactory(
            @Nonnull CoopResidentStateSnapshotCodec snapshotCodec,
            @Nonnull LongSupplier clock) {
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nonnull
    Input create(@Nonnull SpawnReady claim,
                 @Nonnull ResidentRecord resident,
                 @Nonnull String worldName,
                 int chunkX,
                 int chunkZ) {
        validateClaimResident(claim, resident);
        String snapshotJson = requireTextPreserving(resident.snapshotJson(), "snapshotJson");
        String expectedHash = requireText(resident.snapshotHash(), "snapshotHash");
        if (!expectedHash.equals(claim.snapshotHash())
                || !expectedHash.equals(
                ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson))) {
            throw new IllegalArgumentException("resident snapshot hash is not verified");
        }
        CoopResidentStateSnapshotCodec.DecodeResult decoded = snapshotCodec.decode(snapshotJson);
        if (decoded.status() != CoopResidentStateSnapshotCodec.Status.FOUND
                || decoded.snapshot() == null) {
            throw new IllegalArgumentException("resident snapshot cannot be decoded");
        }
        var snapshot = decoded.snapshot();
        if (!claim.sourceNpcUuid().equals(snapshot.npcUuid())
                || !claim.coopId().equalsIgnoreCase(snapshot.coopId())
                || claim.residentSlot() != snapshot.residentSlot()) {
            throw new IllegalArgumentException("resident snapshot metadata does not match claim");
        }
        TameworkOwnerComponent owner = snapshot.owner();
        String exactWorld = requireText(worldName, "worldName").toLowerCase(Locale.ROOT);
        ReleaseRequest request = new ReleaseRequest(
                claim.sourceNpcUuid(), claim.plannedTargetUuid(),
                owner != null ? owner.getOwnerId() : null,
                owner != null ? owner.getOwnerName() : null,
                exactWorld, chunkX, chunkZ, claim.operationId());
        PopulationReleaseCommitRequest durable = new PopulationReleaseCommitRequest(
                claim.operationId(), claim.residentId(), claim.authorityKey(), claim.coopId(),
                claim.residentSlot(), claim.profileId(), claim.plannedTargetUuid(),
                claim.plannedTargetUuid(), claim.snapshotHash(),
                claim.expectedResidentGeneration(), claim.operationGeneration(), clock.getAsLong());
        return new Input(request, durable);
    }

    private static void validateClaimResident(SpawnReady claim, ResidentRecord resident) {
        boolean valid = claim.spawnRequired()
                && claim.operationGeneration() == 1L
                && claim.actualTargetUuid() == null
                && !claim.sourceNpcUuid().equals(claim.plannedTargetUuid())
                && resident.active()
                && resident.state() == ResidentState.RELEASING
                && resident.generation() == claim.releasingResidentGeneration()
                && claim.releasingResidentGeneration()
                    == claim.expectedResidentGeneration() + 1L
                && claim.residentId().equals(resident.residentId())
                && claim.profileId().equals(resident.profileId())
                && claim.authorityKey().equals(resident.authorityKey())
                && claim.coopId().equalsIgnoreCase(resident.coopId())
                && claim.residentSlot() == resident.residentSlot()
                && claim.sourceNpcUuid().equals(resident.residentUuid())
                && claim.sourceNpcUuid().equals(resident.sourceNpcUuid())
                && resident.deployedNpcUuid() == null;
        if (!valid) {
            throw new IllegalArgumentException("release claim and resident do not match");
        }
    }

    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String requireTextPreserving(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
