package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Projection replay and canonical rebuild tests for the derived coop index. */
class CoopResidencyProjectionIndexTest {
    private static final CoopSlotKey SLOT =
            new CoopSlotKey("world", "coop", 1, 2, 3, 0);
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");

    @Test
    void appliesCaptureAndReleaseExactlyOnceBySlotRevision() {
        CoopResidencyProjectionIndex index = new CoopResidencyProjectionIndex();
        CoopResidency residency = residency();
        ProjectionEvent capture = event(
                1,
                new CoopResidencyProjectionChange(
                        SLOT, 1, null, residency, -100
                )
        );

        assertEquals(ProjectionApplyOutcome.APPLIED, index.apply(capture));
        assertEquals(
                ProjectionApplyOutcome.ALREADY_APPLIED,
                index.apply(capture)
        );
        assertEquals(
                PROFILE,
                index.findBySlot(SLOT).orElseThrow().residency().profileId()
        );

        ProjectionEvent release = event(
                2,
                new CoopResidencyProjectionChange(
                        SLOT, 2, residency, null, -90
                )
        );
        assertEquals(ProjectionApplyOutcome.APPLIED, index.apply(release));
        assertTrue(index.findBySlot(SLOT).isEmpty());
        assertTrue(index.findByProfile(PROFILE).isEmpty());
    }

    @Test
    void canonicalRebuildRestoresBothLookupDirections() {
        CoopResidencyProjectionIndex index = new CoopResidencyProjectionIndex();
        CoopOccupancy occupancy = new CoopOccupancy(
                new CoopSlot(SLOT, 7, null, null),
                residency()
        );

        index.rebuild(java.util.List.of(occupancy));

        assertEquals(occupancy, index.findBySlot(SLOT).orElseThrow());
        assertEquals(occupancy, index.findByProfile(PROFILE).orElseThrow());
        assertEquals(java.util.Map.of(SLOT, occupancy), index.snapshot());
    }

    private ProjectionEvent event(
            long sequence,
            CoopResidencyProjectionChange change
    ) {
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                new OperationId(new UUID(0, sequence)),
                CoopResidencyProjectionCodec.EVENT_TYPE,
                CoopResidencyProjectionCodec.aggregateId(SLOT),
                change.slotRevision(),
                CoopResidencyProjectionCodec.VERSION,
                CoopResidencyProjectionCodec.encode(change),
                change.changedAtMs()
        );
    }

    private CoopResidency residency() {
        return new CoopResidency(
                SLOT,
                PROFILE,
                NpcAlias.parse("30000000-0000-0000-0000-000000000001"),
                SnapshotId.parse("40000000-0000-0000-0000-000000000001"),
                -100,
                -100
        );
    }
}
