package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureAttempt;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemEnvelopeCodec.Envelope;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceEvidence.CapturedItemSource;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.IntakeRequest;
import com.alechilles.alecstamework.items.ManagedCoopOccupancyService.CapturePlacement;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.ownership.CoopPopulationCaptureAdmissionService.SourceKind;
import javax.annotation.Nonnull;

/** Remaps one verified portable item snapshot into an exact, item-source-tagged capture attempt. */
final class ManagedCoopCapturedItemAttemptFactory {
    private final CoopResidentStateSnapshotCodec snapshots = new CoopResidentStateSnapshotCodec();

    @Nonnull
    CaptureAttempt build(@Nonnull IntakeRequest request,
                         @Nonnull Envelope envelope,
                         @Nonnull CapturePlacement placement) {
        CoopResidentStateSnapshot portable = envelope.portableSnapshot();
        CoopResidentStateSnapshot housed = new CoopResidentStateSnapshot(
                portable.npcUuid(),
                request.context().coopId(),
                placement.residentSlot(),
                portable.roleId(),
                portable.commandLinks(),
                portable.owner(),
                portable.tamed(),
                portable.npcName(),
                portable.happiness(),
                portable.needs(),
                portable.breeding(),
                portable.leveling(),
                portable.traits(),
                portable.talents(),
                portable.lifeStage(),
                portable.attachments(),
                portable.healthPercent(),
                portable.capturedAtMs()
        );
        String snapshotJson = snapshots.encode(housed);
        snapshotJson = ManagedCoopCaptureSourceEvidence.markCapturedItem(
                snapshotJson,
                new CapturedItemSource(
                        request.playerUuid(),
                        request.hotbarSlot(),
                        request.itemId(),
                        envelope.fingerprint()
                )
        );
        String snapshotHash = ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson);
        return new CaptureAttempt(
                request.context().authorityKey(),
                request.context().coopId(),
                placement.residentSlot(),
                envelope.sourceNpcUuid(),
                envelope.roleId(),
                envelope.ownerUuid(),
                envelope.displayName(),
                envelope.toolIds(),
                SourceKind.CAPTURED_ITEM,
                null,
                false,
                snapshotJson,
                snapshotHash,
                envelope.snapshotVersion(),
                placement.expectedResidentGeneration(),
                placement.existingResidentId(),
                portable.capturedAtMs()
        );
    }
}
