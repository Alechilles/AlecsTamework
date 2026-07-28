package com.alechilles.alecstamework.companion.extension;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rebuild, replay, deletion, and deterministic namespace lookup tests. */
class ProfileExtensionProjectionIndexTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final ProfileExtensionKey KEY =
            new ProfileExtensionKey(PROFILE, "example", "alpha");

    @Test
    void rebuildRetainsTombstoneRevisionAndEventsAdvanceLookup() {
        ProfileExtensionProjectionIndex index =
                new ProfileExtensionProjectionIndex();
        ProfileExtensionData tombstone = new ProfileExtensionData(
                KEY,
                1,
                "{\"old\":true}",
                Sha256Hash.ofUtf8("{\"old\":true}"),
                2,
                -30,
                -20,
                -20L
        );
        index.rebuild(List.of(tombstone));

        assertTrue(index.find(KEY).isEmpty());
        assertEquals(
                ProjectionApplyOutcome.ALREADY_APPLIED,
                index.apply(event(
                        1,
                        new ProfileExtensionMutationOutcome(
                                ProfileExtensionMutationOutcome.Status.APPLIED,
                                KEY,
                                1,
                                "{\"stale\":true}",
                                -25
                        )
                ))
        );

        ProfileExtensionMutationOutcome applied =
                new ProfileExtensionMutationOutcome(
                        ProfileExtensionMutationOutcome.Status.APPLIED,
                        KEY,
                        3,
                        "{\"current\":true}",
                        -10
                );
        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                index.apply(event(2, applied))
        );
        assertEquals(
                "{\"current\":true}",
                index.find(KEY).orElseThrow().jsonPayload()
        );
        assertEquals(
                List.of("alpha"),
                index.namespace(PROFILE, "example")
                        .keySet().stream().toList()
        );

        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                index.apply(event(
                        3,
                        new ProfileExtensionMutationOutcome(
                                ProfileExtensionMutationOutcome.Status.DELETED,
                                KEY,
                                4,
                                null,
                                0
                        )
                ))
        );
        assertTrue(index.find(KEY).isEmpty());
    }

    private ProjectionEvent event(
            long sequence,
            ProfileExtensionMutationOutcome outcome
    ) {
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                OperationId.parse(String.format(
                        "20000000-0000-0000-0000-%012d",
                        sequence
                )),
                ProfileExtensionMutationEventCodec.EVENT_TYPE,
                outcome.key().aggregateId(),
                outcome.revision(),
                ProfileExtensionMutationEventCodec.VERSION,
                ProfileExtensionMutationEventCodec.encode(outcome),
                outcome.updatedAtMs()
        );
    }
}
