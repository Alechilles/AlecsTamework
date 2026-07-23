package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Rebuild and at-least-once event tests for provisioning lookup state. */
class ProvisioningProjectionIndexTest {
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000097");

    @Test
    void appliesOneImmutableRecordIdempotently() {
        ProvisioningProjectionIndex index =
                new ProvisioningProjectionIndex();
        ProvisioningRecord record = record("profile-a", -5_000);
        ProjectionEvent event = event(record, 1);

        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                index.apply(event)
        );
        assertEquals(
                ProjectionApplyOutcome.ALREADY_APPLIED,
                index.apply(event)
        );
        assertEquals(
                record,
                index.findByOrigin(record.origin()).orElseThrow()
        );
    }

    @Test
    void rebuildRejectsDuplicateOriginAndReplacesOldState() {
        ProvisioningProjectionIndex index =
                new ProvisioningProjectionIndex();
        ProvisioningRecord first = record("profile-a", -5_000);
        index.rebuild(List.of(first));
        assertEquals(1, index.snapshot().size());

        ProvisioningRecord second = record("profile-b", -4_000);
        index.rebuild(List.of(second));
        assertEquals(
                second,
                index.findByProfile(second.profileId()).orElseThrow()
        );
        assertEquals(1, index.snapshot().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> index.rebuild(List.of(second, second))
        );
    }

    private ProvisioningRecord record(String key, long time) {
        ProvisioningOrigin origin =
                new ProvisioningOrigin("test:projection", key);
        return new ProvisioningRecord(
                origin.profileId(),
                origin,
                new UUID(0, Math.abs(key.hashCode())),
                7,
                OPERATION,
                time
        );
    }

    private ProjectionEvent event(
            ProvisioningRecord record,
            long sequence
    ) {
        ProjectionEventDraft draft =
                ProvisioningRecordChangeCodec.draft(
                        OPERATION, record
                );
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                draft.operationId(),
                draft.eventType(),
                draft.aggregateId(),
                draft.aggregateRevision(),
                draft.payloadVersion(),
                draft.payloadJson(),
                draft.createdAtMs()
        );
    }
}
