package com.alechilles.alecstamework.companion.extension;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rebuild, replay, deletion, and deterministic namespace lookup tests. */
class ProfileExtensionProjectionIndexTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final ProfileExtensionKey KEY =
            new ProfileExtensionKey(PROFILE, "example", "alpha");
    private static final ProfileId OTHER_PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000002");

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

    @Test
    void namespaceLookupStaysScopedAcrossReplayDeleteAndRebuild() {
        ProfileExtensionProjectionIndex index =
                new ProfileExtensionProjectionIndex();
        ProfileExtensionKey alpha = new ProfileExtensionKey(
                PROFILE, "menu", "alpha"
        );
        ProfileExtensionKey bravo = new ProfileExtensionKey(
                PROFILE, "menu", "bravo"
        );
        ProfileExtensionKey otherNamespace = new ProfileExtensionKey(
                PROFILE, "other", "alpha"
        );
        ProfileExtensionKey otherProfile = new ProfileExtensionKey(
                OTHER_PROFILE, "menu", "alpha"
        );
        index.rebuild(List.of(
                data(bravo, 1, "{\"value\":\"old\"}"),
                data(alpha, 1, "{\"value\":\"alpha\"}"),
                data(otherNamespace, 1, "{\"value\":\"other\"}"),
                data(otherProfile, 1, "{\"value\":\"profile\"}")
        ));

        Map<String, ProfileExtensionProjectionValue> menu =
                index.namespace(PROFILE, "menu");
        assertEquals(List.of("alpha", "bravo"), menu.keySet().stream().toList());
        assertThrows(UnsupportedOperationException.class, () -> menu.remove("alpha"));

        ProfileExtensionMutationOutcome updated =
                new ProfileExtensionMutationOutcome(
                        ProfileExtensionMutationOutcome.Status.APPLIED,
                        bravo,
                        2,
                        "{\"value\":\"new\"}",
                        20
                );
        assertEquals(ProjectionApplyOutcome.APPLIED, index.apply(event(1, updated)));
        assertEquals(
                ProjectionApplyOutcome.ALREADY_APPLIED,
                index.apply(event(2, updated))
        );
        assertEquals(
                "{\"value\":\"new\"}",
                index.namespace(PROFILE, "menu").get("bravo").jsonPayload()
        );

        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                index.apply(event(
                        3,
                        new ProfileExtensionMutationOutcome(
                                ProfileExtensionMutationOutcome.Status.DELETED,
                                alpha,
                                2,
                                null,
                                30
                        )
                ))
        );
        assertEquals(
                List.of("bravo"),
                index.namespace(PROFILE, "menu").keySet().stream().toList()
        );
        assertEquals(
                "{\"value\":\"other\"}",
                index.namespace(PROFILE, "other").get("alpha").jsonPayload()
        );
        assertEquals(
                "{\"value\":\"profile\"}",
                index.namespace(OTHER_PROFILE, "menu")
                        .get("alpha").jsonPayload()
        );

        ProfileExtensionKey charlie = new ProfileExtensionKey(
                PROFILE, "menu", "charlie"
        );
        index.rebuild(List.of(data(charlie, 1, "{\"value\":\"fresh\"}")));
        assertEquals(
                List.of("charlie"),
                index.namespace(PROFILE, "menu").keySet().stream().toList()
        );
        assertTrue(index.namespace(PROFILE, "other").isEmpty());
        assertTrue(index.namespace(OTHER_PROFILE, "menu").isEmpty());
    }

    private ProfileExtensionData data(
            ProfileExtensionKey key,
            long revision,
            String jsonPayload
    ) {
        return new ProfileExtensionData(
                key,
                1,
                jsonPayload,
                Sha256Hash.ofUtf8(jsonPayload),
                revision,
                0,
                0,
                null
        );
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
