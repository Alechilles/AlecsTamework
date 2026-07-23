package com.alechilles.alecstamework.companion.dormant;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Shared death/lost payload and positive-evidence vocabulary contracts. */
class CompanionDormantTransitionDefinitionTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final LifecycleRevision REVISION = new LifecycleRevision(4);
    private static final String PAYLOAD = "{\"health\":0}";

    @Test
    void deathAndLostRoundTripThroughOneDefinition() {
        for (DormantSourceEvidence.Kind kind
                : DormantSourceEvidence.Kind.values()) {
            CompanionDormantTransitionRequest request = request(kind);

            assertEquals(
                    request,
                    CompanionDormantTransitionDefinition.INSTANCE.decode(
                            CompanionDormantTransitionDefinition.INSTANCE
                                    .encode(request)
                    )
            );
            assertEquals(kind.targetState(), request.targetState());
            CompanionDormantTransitionOutcome outcome =
                    new CompanionDormantTransitionOutcome(
                            PROFILE,
                            kind.targetState(),
                            request.snapshot().snapshotId(),
                            REVISION.next(),
                            request.source().receiptKey(),
                            -500
                    );
            assertEquals(
                    outcome,
                    CompanionDormantTransitionEventCodec.decode(
                            CompanionDormantTransitionEventCodec.VERSION,
                            CompanionDormantTransitionEventCodec.encode(outcome)
                    )
            );
        }
    }

    @Test
    void snapshotMustMatchExactPreTransitionState() {
        CompanionSnapshot wrongRevision = snapshot(
                DormantSourceEvidence.Kind.DEATH_COMPONENT,
                REVISION.next()
        );
        DormantSourceEvidence evidence =
                evidence(DormantSourceEvidence.Kind.DEATH_COMPONENT);

        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionDormantTransitionRequest(
                        PROFILE, REVISION, wrongRevision, evidence, -600
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionDormantTransitionRequest(
                        PROFILE,
                        REVISION,
                        snapshot(
                                DormantSourceEvidence.Kind.DESTRUCTIVE_REMOVAL,
                                REVISION
                        ),
                        evidence,
                        -600
                )
        );
    }

    @Test
    void evidenceVocabularyContainsNoAbsenceInference() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DormantSourceEvidence.Kind.valueOf("ABSENT")
        );
        assertEquals(
                LifecycleState.DEAD_REVIVABLE,
                DormantSourceEvidence.Kind.DEATH_COMPONENT.targetState()
        );
        assertEquals(
                LifecycleState.LOST,
                DormantSourceEvidence.Kind.WORLD_DELETION.targetState()
        );
    }

    private CompanionDormantTransitionRequest request(
            DormantSourceEvidence.Kind kind
    ) {
        return new CompanionDormantTransitionRequest(
                PROFILE,
                REVISION,
                snapshot(kind, REVISION),
                evidence(kind),
                -600
        );
    }

    private DormantSourceEvidence evidence(
            DormantSourceEvidence.Kind kind
    ) {
        return new DormantSourceEvidence(
                ALIAS,
                "world",
                kind,
                new ReconciliationGeneration(8),
                "receipt-" + kind.name().toLowerCase(),
                -700
        );
    }

    private CompanionSnapshot snapshot(
            DormantSourceEvidence.Kind kind,
            LifecycleRevision revision
    ) {
        return new CompanionSnapshot(
                SnapshotId.parse("50000000-0000-0000-0000-000000000001"),
                PROFILE,
                kind.snapshotKind(),
                1,
                PAYLOAD,
                Sha256Hash.ofUtf8(PAYLOAD),
                revision,
                true,
                -650
        );
    }
}
